package com.mogy.optimizer.packs;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.config.MogyConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Short-lived cache of directory listings, used by the launcher folder-search hook
 * ({@link com.mogy.optimizer.mixin.LunarFolderSearchMixin}).
 *
 * <h2>Root cause</h2>
 * Lunar Client's resource pack menu shows packs in folders. Every time the pack list is
 * rebuilt (opening the menu, and <em>every</em> select, remove or move click), Lunar looks
 * up each pack's file with a recursive depth-first search of the whole resource pack
 * folder, on the render thread. It runs one search per pack and shares nothing between
 * them. Packs that are not files at all (the dozens of built-in and mod packs) are never
 * found, so each one walks the entire tree. With a few hundred packs and a few thousand
 * loose files, that is millions of file-system calls: a measured 53-second freeze per list
 * rebuild, long enough for servers to time the player out.
 *
 * <h2>Fix</h2>
 * Within one list rebuild, a directory's contents do not change, and Lunar's two listing
 * filters are fixed (non-capturing) lambdas, so every search would get the same answer for
 * the same directory. The listings are therefore cached by (directory, filter): each
 * directory is read from disk once per rebuild instead of once per pack. Results are
 * identical, just not recomputed. The cache is cleared at the start of every list rebuild
 * and entries expire after {@value #MAX_AGE_MS} ms, so added or removed packs always show up.
 */
public final class DirectoryListingCache {
    private static final long MAX_AGE_MS = 5000;
    private static final Map<Key, Listing> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicBoolean STATUS_WRITTEN = new AtomicBoolean();

    private record Key(String directory, Object filter) {
    }

    private record Listing(File[] files, long createdMs) {
    }

    private DirectoryListingCache() {
    }

    /** Returns the cached listing of {@code directory} for {@code filter}, listing it with {@code lister} on a miss. */
    public static File[] list(File directory, Object filter, Supplier<File[]> lister) {
        if (!MogyConfig.get().cacheLauncherFolderSearch || directory == null || !isStateless(filter)) {
            return lister.get();
        }
        writeStatusOnce();
        long now = System.currentTimeMillis();
        Key key = new Key(directory.getAbsolutePath(), filter);
        Listing cached = CACHE.get(key);
        if (cached != null && now - cached.createdMs() < MAX_AGE_MS) {
            HITS.incrementAndGet();
            return cached.files() == null ? null : cached.files().clone();
        }
        MISSES.incrementAndGet();
        File[] files = lister.get();
        CACHE.put(key, new Listing(files == null ? null : files.clone(), now));
        return files;
    }

    /** Called at the start of every pack list rebuild, so each rebuild sees the folder as it is now. */
    public static void invalidate() {
        CACHE.clear();
    }

    public static String stats() {
        return "launcher folder-search cache: " + HITS.get() + " listings reused, " + MISSES.get() + " read from disk";
    }

    /**
     * Only filters without captured state may be shared between searches. Lunar's are
     * non-capturing lambdas; anything that captures variables is not cached.
     */
    private static boolean isStateless(Object filter) {
        return filter != null && filter.getClass().getDeclaredFields().length == 0;
    }

    /** Leaves a one-line note proving the hook is active (some launchers hide mod log output). */
    private static void writeStatusOnce() {
        if (STATUS_WRITTEN.compareAndSet(false, true)) {
            try {
                Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("mogy-optimizer-status.txt"),
                        "Launcher folder-search cache active since " + LocalDateTime.now() + "\n", StandardCharsets.UTF_8);
            } catch (Throwable t) {
                MogyOptimizerClient.LOGGER.debug("Could not write status file", t);
            }
        }
    }
}
