package com.mogy.optimizer.packs;

import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.mixin.FileResourcesSupplierAccessor;
import com.mogy.optimizer.mixin.PathResourcesSupplierAccessor;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Makes every pack folder scan after the first one fast by remembering each pack's metadata.
 *
 * <h2>Root cause</h2>
 * Every time the pack folder is scanned (game start, opening the resource pack menu, any
 * folder change while it is open), {@code Pack.readPackMetadata} runs for every pack. It
 * opens the archive (reading a zip's whole central directory, which for a large 128x pack
 * means thousands of entries), reads and parses {@code pack.mcmeta}, and logs a full stack
 * trace for every malformed one. The result is identical each time unless the file changed.
 * With hundreds of packs this is what makes a scan take tens of seconds. Launchers that
 * build their own pack menu on top of the scan (Lunar's folder view) cannot show anything
 * until it finishes.
 *
 * <h2>Fix</h2>
 * The parsed {@link Pack.Metadata} is cached, keyed by the pack's file path together with
 * its size and modified time (for folder packs, those of {@code pack.mcmeta} and the
 * folder). An unchanged pack is answered from memory without opening the archive; a
 * changed one gets a new key and is read again. Packs that failed to parse are cached too,
 * so a broken pack costs one warning instead of a stack trace on every scan. The game's
 * own startup scan fills the cache, so even the first menu open in a session is fast.
 *
 * <p>Thread-safe: scans run on the render thread at startup and on the pack worker later.
 */
public final class PackMetadataCache {
    private static final int MAX_ENTRIES = 8192;
    private static final Map<String, Optional<Pack.Metadata>> CACHE = new ConcurrentHashMap<>();

    private PackMetadataCache() {
    }

    public static Pack.Metadata getOrLoad(PackLocationInfo location, Pack.ResourcesSupplier supplier,
                                          PackFormat format, PackType type, Supplier<Pack.Metadata> loader) {
        String key = MogyConfig.get().cachePackMetadata ? key(location, supplier, format, type) : null;
        if (key == null) {
            return loader.get(); // unknown pack source or caching disabled: vanilla behaviour
        }
        Optional<Pack.Metadata> cached = CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Pack.Metadata metadata = loader.get();
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear(); // crude bound; refills on the next scan
        }
        CACHE.put(key, Optional.ofNullable(metadata));
        return metadata;
    }

    /** Builds the cache key, or returns null when the pack's file cannot be identified. */
    private static String key(PackLocationInfo location, Pack.ResourcesSupplier supplier, PackFormat format, PackType type) {
        Path path;
        if (supplier instanceof FileResourcesSupplierAccessor file) {
            path = file.mogy$getContent().toPath();
        } else if (supplier instanceof PathResourcesSupplierAccessor folder) {
            path = folder.mogy$getContent();
        } else {
            return null;
        }
        String stamp = stamp(path);
        if (stamp == null) {
            return null;
        }
        return type + "|" + format + "|" + location.id() + "|" + path.toAbsolutePath() + "|" + stamp;
    }

    private static String stamp(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String stamp = attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
            if (attributes.isDirectory()) {
                Path mcmeta = path.resolve("pack.mcmeta");
                if (Files.exists(mcmeta)) {
                    BasicFileAttributes meta = Files.readAttributes(mcmeta, BasicFileAttributes.class);
                    stamp += "/" + meta.size() + ":" + meta.lastModifiedTime().toMillis();
                } else {
                    stamp += "/none";
                }
            }
            return stamp;
        } catch (Throwable t) {
            return null;
        }
    }
}
