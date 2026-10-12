package com.mogy.optimizer.packs;

import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.mixin.AbstractPackResourcesAccessor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;

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
 * Every time the pack folder is scanned (game start, pressing Done in the resource pack
 * menu, drag-and-drop), {@code Pack.create} runs for every pack. It opens the archive
 * (reading a zip's whole central directory, which for a large 128x pack means thousands of
 * entries) and reads and parses {@code pack.mcmeta}. The result is identical each time
 * unless the file changed. With hundreds of packs this makes every scan slow, and the scan
 * after pressing Done runs on the render thread.
 *
 * <h2>Fix</h2>
 * The parsed {@link PackMetadataSection} is cached, keyed by the pack's file path together
 * with its size and modified time (for folder packs, those of {@code pack.mcmeta} and the
 * folder). An unchanged pack is answered from memory and its archive is never opened; a
 * changed one gets a new key and is read again. Packs without a {@code pack.mcmeta} are
 * cached too. The game's own startup scan fills the cache, so later scans are fast.
 *
 * <p>Thread-safe: scans run on the render thread and on the pack worker.
 */
public final class PackMetadataCache {
    private static final int MAX_ENTRIES = 8192;
    private static final Map<String, Optional<Object>> CACHE = new ConcurrentHashMap<>();

    private PackMetadataCache() {
    }

    public static Object getOrLoad(PackResources resources, MetadataSectionSerializer<?> serializer, Supplier<Object> loader) {
        String key = MogyConfig.get().cachePackMetadata && serializer == PackMetadataSection.SERIALIZER ? key(resources) : null;
        if (key == null) {
            return loader.get(); // built-in pack, other metadata, or caching disabled: vanilla behaviour
        }
        Optional<Object> cached = CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Object metadata = loader.get(); // an IOException propagates to Pack.create, exactly as in vanilla, and is not cached
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear(); // crude bound; refills on the next scan
        }
        CACHE.put(key, Optional.ofNullable(metadata));
        return metadata;
    }

    /** Builds the cache key, or returns null when the pack's file cannot be identified. */
    private static String key(PackResources resources) {
        if (!(resources instanceof AbstractPackResourcesAccessor)) {
            return null;
        }
        Path path = ((AbstractPackResourcesAccessor) resources).mogy$getFile().toPath();
        String stamp = stamp(path);
        if (stamp == null) {
            return null;
        }
        return path.toAbsolutePath() + "|" + stamp;
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
