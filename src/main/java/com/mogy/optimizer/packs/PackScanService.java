package com.mogy.optimizer.packs;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.mixin.PackRepositoryAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous replacement for {@code PackSelectionScreen.reload()}.
 *
 * <h2>Root cause</h2>
 * Vanilla calls {@code reload()} from {@code init()} every time the menu opens, again
 * whenever the folder watcher sees a change, and after a drag-and-drop copy. It runs
 * {@code PackRepository.reload()} on the render thread, which asks every
 * {@link RepositorySource} to rediscover its packs. A large folder freezes the game on
 * every open.
 *
 * <h2>Fix</h2>
 * Discovery runs on {@link PackWorker} into a <em>fresh</em> map, so the shared
 * {@code PackRepository} is never touched off-thread. The result is swapped in on the
 * render thread with exactly the steps vanilla uses (see
 * {@code PackSelectionScreenMixin#mogy$applyScan}). The scan itself is fast because
 * {@link PackMetadataCache} answers unchanged packs from memory.
 *
 * <p>The scan always runs when the menu opens, even if the folder looks unchanged. Some
 * launchers build their own pack menu as a side effect of this scan (Lunar's folder view),
 * so skipping it would leave their menu empty.
 *
 * <p>Generations keep this safe. Each request bumps the screen's counter, and closing the
 * screen bumps it too. A scan whose generation is no longer current is skipped before it
 * starts (so bursts of file events coalesce into one scan) and discarded when it finishes
 * (so nothing is ever applied to a closed screen).
 */
public final class PackScanService {
    private static final AtomicInteger COMPLETED_SCANS = new AtomicInteger();

    private PackScanService() {
    }

    /** Render thread, once the client has started: decode icons in the background so the menu shows them immediately. */
    public static void onClientStarted(Minecraft minecraft) {
        if (MogyConfig.get().asyncPackIcons && MogyConfig.get().preloadPackIcons) {
            Path packDir = minecraft.getResourcePackDirectory();
            for (Pack pack : minecraft.getResourcePackRepository().getAvailablePacks()) {
                PackIconCache.get(pack, packDir, null); // queues the decode; the placeholder result is unused
            }
        }
    }

    /** Number of scans that were applied to the repository (for diagnostics and tests). */
    public static int completedScans() {
        return COMPLETED_SCANS.get();
    }

    /** Render thread. Starts a background rescan for the given screen. */
    public static void requestScan(AsyncPackScreen screen) {
        int generation = screen.mogy$scanGeneration().incrementAndGet();
        // Copy the source list on the render thread. The sources themselves are only read.
        List<RepositorySource> sources = List.copyOf(((PackRepositoryAccessor) screen.mogy$repository()).mogy$getSources());
        Path packDir = screen.mogy$packDir();

        boolean queued = PackWorker.submit(() -> {
            if (isStale(screen, generation)) {
                return; // a newer request is already queued behind us
            }
            long start = System.nanoTime();
            Map<String, Pack> available = new TreeMap<>();
            try {
                // Same as PackRepository.discoverAvailable(), but into our own map.
                for (RepositorySource source : sources) {
                    source.loadPacks(pack -> available.put(pack.getId(), pack));
                }
            } catch (Throwable t) {
                MogyOptimizerClient.LOGGER.warn("Background pack scan failed; falling back to vanilla reload", t);
                Minecraft.getInstance().execute(() -> {
                    if (!isStale(screen, generation)) {
                        screen.mogy$applyScan(null);
                    }
                });
                return;
            }
            // File stats for icon cache revalidation happen here, off the render thread.
            Map<String, String> iconKeys = PackIconCache.computeFileKeys(available.values(), packDir);
            long scanMs = (System.nanoTime() - start) / 1_000_000;
            MogyOptimizerClient.LOGGER.info("Scanned resource pack folder: {} packs in {} ms", available.size(), scanMs);

            Minecraft.getInstance().execute(() -> {
                PackIconCache.revalidate(iconKeys);
                if (!isStale(screen, generation)) {
                    screen.mogy$applyScan(available);
                    COMPLETED_SCANS.incrementAndGet();
                }
            });
        });

        if (!queued) {
            screen.mogy$applyScan(null);
        }
    }

    private static boolean isStale(AsyncPackScreen screen, int generation) {
        return screen.mogy$isClosed() || screen.mogy$scanGeneration().get() != generation;
    }
}
