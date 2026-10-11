package com.mogy.optimizer.packs;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.config.MogyConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Asynchronous, cached replacement for {@code PackSelectionScreen.getPackIcon()}.
 *
 * <h2>Root cause</h2>
 * Vanilla loads a pack's icon the first time its list entry is drawn, on the render
 * thread. It reopens the pack archive, decodes the <em>full-resolution</em>
 * {@code pack.png} (often 512 to 4096 px, even though it is drawn at 32 px), and uploads
 * it as a GPU texture. Scrolling a long list stalls on every new row. The cache lives on
 * the screen and is cleared by every folder change, so the work repeats each time. The
 * decode is guarded by {@code catch (Exception)}, so a huge or malicious PNG that throws
 * {@link OutOfMemoryError} crashes the game.
 *
 * <h2>Fix</h2>
 * <ul>
 *   <li>The render thread only does a map lookup. A missing icon returns vanilla's default
 *       icon and queues a decode on {@link PackWorker}.</li>
 *   <li>The worker reads at most {@code maxIconBytes}, checks the PNG header dimensions
 *       before decoding, and downscales to {@value #ICON_SIZE} px. GPU memory per icon is
 *       capped at 16 KB.</li>
 *   <li>Decoded images reach the GPU in small batches per client tick
 *       ({@link #drainUploads}), so a big list never causes a frame spike.</li>
 *   <li>The cache is static and keyed by pack id, so it survives reopening the menu and
 *       folder rescans. After each scan, entries whose file changed (size or modified
 *       time) are dropped ({@link #revalidate}), so edited packs still refresh.</li>
 *   <li>Least recently used textures beyond {@code iconCacheSize} are released through
 *       {@link TextureManager#release}, so memory stays bounded.</li>
 *   <li>Every failure, including {@link Throwable}, is caught per icon and shows the
 *       default icon.</li>
 * </ul>
 *
 * <p>Threading: {@link #CACHE} is only touched on the render thread. {@link #PENDING} and
 * {@link #READY} are concurrent and form the hand-off between the threads.
 */
public final class PackIconCache {
    /** Entries are drawn at 32 px. 64 px keeps them sharp at GUI scale 2 without wasting memory. */
    private static final int ICON_SIZE = 64;
    /** Refuse to decode images above this pixel count, whatever the file size (a compressed PNG can be tiny but huge). */
    private static final long MAX_DECODE_PIXELS = 4096L * 4096L;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Render thread only. Access-ordered, so iteration starts at the least recently used icon. */
    private static final LinkedHashMap<String, CachedIcon> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Queue<DecodedIcon> READY = new ConcurrentLinkedQueue<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static int textureCounter;

    /** {@code texture == null} means "this pack has no usable icon": draw the default and don't retry. */
    private record CachedIcon(String fileKey, Identifier texture) {
    }

    /** {@code image == null} means decoding failed or the pack has no icon. */
    private record DecodedIcon(String packId, String fileKey, NativeImage image) {
    }

    private PackIconCache() {
    }

    /** Render thread. Returns the cached icon, or {@code fallback} while it loads. Never blocks. */
    public static Identifier get(Pack pack, Path packDir, Identifier fallback) {
        String id = pack.getId();
        CachedIcon cached = CACHE.get(id);
        if (cached != null) {
            return cached.texture() != null ? cached.texture() : fallback;
        }
        if (PENDING.add(id) && !PackWorker.submit(() -> READY.add(decode(pack, packDir)))) {
            PENDING.remove(id); // worker is gone (game shutting down)
        }
        return fallback;
    }

    /** Render thread. Number of icons currently held as GPU textures (for diagnostics and tests). */
    public static int loadedIconCount() {
        int count = 0;
        for (CachedIcon icon : CACHE.values()) {
            if (icon.texture() != null) {
                count++;
            }
        }
        return count;
    }

    /** Render thread, once per client tick. Uploads a bounded number of decoded icons. */
    public static void drainUploads(Minecraft minecraft) {
        if (READY.isEmpty()) {
            return;
        }
        TextureManager textures = minecraft.getTextureManager();
        int budget = MogyConfig.get().iconUploadsPerTick;
        DecodedIcon decoded;
        while (budget-- > 0 && (decoded = READY.poll()) != null) {
            PENDING.remove(decoded.packId());
            Identifier texture = null;
            if (decoded.image() != null) {
                texture = Identifier.fromNamespaceAndPath(MogyOptimizerClient.MOD_ID, "pack_icon/" + textureCounter++);
                try {
                    Identifier name = texture;
                    textures.register(texture, new DynamicTexture(name::toString, decoded.image()));
                } catch (Throwable t) {
                    decoded.image().close();
                    warnOnce(decoded.packId(), "upload", t);
                    texture = null;
                }
            }
            CachedIcon previous = CACHE.put(decoded.packId(), new CachedIcon(decoded.fileKey(), texture));
            if (previous != null) {
                release(textures, previous);
            }
        }
        evictOverflow(textures);
    }

    /**
     * Render thread, after a rescan. Drops icons whose pack file changed on disk, or
     * whose file pack no longer exists, so the next draw decodes the new icon.
     */
    public static void revalidate(Map<String, String> currentFileKeys) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        Iterator<Map.Entry<String, CachedIcon>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedIcon> entry = it.next();
            String current = currentFileKeys.get(entry.getKey());
            boolean removedFilePack = current == null && entry.getKey().startsWith("file/");
            if (removedFilePack || (current != null && !current.equals(entry.getValue().fileKey()))) {
                release(textures, entry.getValue());
                it.remove();
            }
        }
    }

    /** Worker thread. Computes the change-detection key for every pack (file size + modified time). */
    public static Map<String, String> computeFileKeys(Collection<Pack> packs, Path packDir) {
        Map<String, String> keys = new HashMap<>();
        for (Pack pack : packs) {
            keys.put(pack.getId(), fileKey(pack.getId(), packDir));
        }
        return keys;
    }

    private static String fileKey(String packId, Path packDir) {
        // Folder packs are "file/<name>"; built-in and mod packs can only change with a restart.
        if (packDir == null || !packId.startsWith("file/")) {
            return "static";
        }
        try {
            Path path = packDir.resolve(packId.substring("file/".length()));
            if (Files.isDirectory(path)) {
                path = path.resolve("pack.png");
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
        } catch (Throwable t) {
            return "missing";
        }
    }

    /** Worker thread. Opens the pack, reads and validates pack.png, decodes and downscales it. */
    private static DecodedIcon decode(Pack pack, Path packDir) {
        String id = pack.getId();
        String key = fileKey(id, packDir);
        try (PackResources resources = pack.open()) {
            IoSupplier<InputStream> supplier = resources.getRootResource("pack.png");
            if (supplier == null) {
                return new DecodedIcon(id, key, null);
            }
            byte[] bytes;
            try (InputStream in = supplier.get()) {
                bytes = readCapped(in, MogyConfig.get().maxIconBytes);
            }
            if (bytes == null) {
                warnOnce(id, "icon larger than maxIconBytes", null);
                return new DecodedIcon(id, key, null);
            }
            if (!hasSafeDimensions(bytes)) {
                warnOnce(id, "icon is not a PNG or its dimensions are too large", null);
                return new DecodedIcon(id, key, null);
            }
            return new DecodedIcon(id, key, downscale(NativeImage.read(bytes)));
        } catch (Throwable t) {
            // Includes OutOfMemoryError from a decompression bomb, which vanilla would crash on.
            warnOnce(id, "decode", t);
            return new DecodedIcon(id, key, null);
        }
    }

    /** Reads the whole stream, or returns null once it exceeds {@code limit} bytes. */
    private static byte[] readCapped(InputStream in, int limit) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Reads width and height from the PNG IHDR chunk, so oversized images are rejected before decoding. */
    private static boolean hasSafeDimensions(byte[] png) {
        if (png.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        long width = readInt(png, 16) & 0xFFFFFFFFL;
        long height = readInt(png, 20) & 0xFFFFFFFFL;
        return width > 0 && height > 0 && width * height <= MAX_DECODE_PIXELS;
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16) | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    /** Scales to ICON_SIZE x ICON_SIZE. Vanilla stretches the whole image into a square too, so this looks the same. */
    private static NativeImage downscale(NativeImage full) {
        if (full.getWidth() <= ICON_SIZE && full.getHeight() <= ICON_SIZE) {
            return full;
        }
        try {
            NativeImage small = new NativeImage(ICON_SIZE, ICON_SIZE, false);
            full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
            return small;
        } finally {
            full.close();
        }
    }

    private static void evictOverflow(TextureManager textures) {
        int limit = MogyConfig.get().iconCacheSize;
        Iterator<CachedIcon> it = CACHE.values().iterator();
        while (CACHE.size() > limit && it.hasNext()) {
            release(textures, it.next());
            it.remove();
        }
    }

    private static void release(TextureManager textures, CachedIcon icon) {
        if (icon.texture() != null) {
            textures.release(icon.texture());
        }
    }

    private static void warnOnce(String packId, String what, Throwable t) {
        if (WARNED.add(packId)) {
            if (t != null) {
                MogyOptimizerClient.LOGGER.warn("Pack icon for '{}' unavailable ({}); using default icon", packId, what, t);
            } else {
                MogyOptimizerClient.LOGGER.warn("Pack icon for '{}' unavailable ({}); using default icon", packId, what);
            }
        }
    }
}
