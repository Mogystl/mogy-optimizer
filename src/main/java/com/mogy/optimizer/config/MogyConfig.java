package com.mogy.optimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mogy.optimizer.MogyOptimizerClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-tunable settings, stored as JSON in {@code config/mogy-optimizer.json}.
 *
 * <p>Every fix in the mod can be switched off here, so a user who hits a driver or
 * platform quirk can fall back to vanilla behaviour without removing the mod. Values
 * are clamped on load, so a hand-edited file can never put the game in a bad state.
 */
public final class MogyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MogyConfig instance = new MogyConfig();

    public enum FullscreenMode {
        /** Undecorated window covering the monitor; stays under desktop composition (capture-friendly). */
        BORDERLESS,
        /** Vanilla behaviour: GLFW monitor-attached exclusive fullscreen. */
        EXCLUSIVE
    }

    // --- Display / screenshare -------------------------------------------------------

    /** How the fullscreen toggle (F11 / video settings) behaves. */
    public FullscreenMode fullscreenMode = FullscreenMode.BORDERLESS;

    /**
     * Extra pixels added to the borderless window's height. A window that exactly covers
     * a monitor can be silently promoted to exclusive "independent flip" by the driver,
     * which brings back the capture freezes. One off-screen pixel row prevents that.
     * Set to 0 if your setup does not need it.
     */
    public int borderlessOverscanPixels = 1;

    /** Stops GLFW from minimizing a fullscreen window when it loses focus. */
    public boolean disableAutoIconify = true;

    /** With vsync on, use adaptive vsync (late frames tear instead of halving the frame rate). */
    public boolean adaptiveVsync = true;

    // --- Resource pack menu ----------------------------------------------------------

    /** Rescan the resource pack folder on a background thread instead of the render thread. */
    public boolean asyncPackScan = true;

    /**
     * Remember each pack's metadata (keyed by file size and date) so folder scans after the
     * first one do not reopen every archive. This is what makes the menu fill quickly.
     */
    public boolean cachePackMetadata = true;

    /** Drop the "Found non-pack entry" log line that is written for every loose file during a scan. */
    public boolean quietPackScanLog = true;

    /**
     * Lunar Client: reuse folder listings while its pack menu looks up each pack's file, instead
     * of walking the whole resource pack folder once per pack (a ~50 s freeze on big folders).
     */
    public boolean cacheLauncherFolderSearch = true;

    /** Decode and downscale pack icons on a background thread, with a persistent cache. */
    public boolean asyncPackIcons = true;

    /** Decode every pack's icon in the background at game start, so the menu shows them immediately. */
    public boolean preloadPackIcons = true;

    /** Largest pack.png (in bytes) that will be decoded. Bigger icons show the default icon. */
    public int maxIconBytes = 4 * 1024 * 1024;

    /** Number of decoded icons kept as GPU textures. Least recently used ones are released. */
    public int iconCacheSize = 512;

    /** How many finished icons may be uploaded to the GPU per client tick. */
    public int iconUploadsPerTick = 16;

    // --- Diagnostics -----------------------------------------------------------------

    /**
     * When the game freezes for 3+ seconds, write what the render thread was doing to
     * {@code config/mogy-optimizer-stalls.txt}. Costs nothing while the game runs normally.
     */
    public boolean freezeReports = true;

    public static MogyConfig get() {
        return instance;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("mogy-optimizer.json");
        MogyConfig loaded = null;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, MogyConfig.class);
            } catch (IOException | JsonParseException e) {
                MogyOptimizerClient.LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        instance = loaded != null ? loaded : new MogyConfig();
        instance.validate();
        // Rewrite the file so newly added options show up with their defaults.
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            MogyOptimizerClient.LOGGER.warn("Could not write {}", file, e);
        }
    }

    private void validate() {
        if (fullscreenMode == null) {
            fullscreenMode = FullscreenMode.BORDERLESS;
        }
        borderlessOverscanPixels = clamp(borderlessOverscanPixels, 0, 2);
        maxIconBytes = clamp(maxIconBytes, 64 * 1024, 32 * 1024 * 1024);
        iconCacheSize = clamp(iconCacheSize, 32, 4096);
        iconUploadsPerTick = clamp(iconUploadsPerTick, 1, 256);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
