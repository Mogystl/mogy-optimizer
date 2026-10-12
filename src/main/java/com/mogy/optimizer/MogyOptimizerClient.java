package com.mogy.optimizer;

import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.diagnostics.StallWatchdog;
import com.mogy.optimizer.packs.PackIconCache;
import com.mogy.optimizer.packs.PackScanService;
import com.mogy.optimizer.packs.PackWorker;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for Mogy Optimizer (Minecraft 1.16.1 build).
 *
 * <p>The mod has two independent modules:
 * <ul>
 *   <li>{@code display}: fixes fullscreen/screenshare stutter. It works entirely through
 *       {@link com.mogy.optimizer.mixin.WindowMixin}, so nothing needs wiring here.</li>
 *   <li>{@code packs}: makes the resource pack menu fast and crash-free. Its background
 *       worker and the per-tick icon upload are driven from the lifecycle hooks below.</li>
 * </ul>
 * Lifecycle events come from {@link com.mogy.optimizer.mixin.MinecraftLifecycleMixin} rather
 * than Fabric API, so only Fabric Loader is required.
 */
public final class MogyOptimizerClient implements ClientModInitializer {
    public static final String MOD_ID = "mogy-optimizer";
    public static final Logger LOGGER = LogManager.getLogger("Mogy Optimizer");

    private static boolean started;

    @Override
    public void onInitializeClient() {
        MogyConfig.load();
        LOGGER.info("Mogy Optimizer initialized (fullscreen={}, asyncPackScan={}, asyncPackIcons={})",
                MogyConfig.get().fullscreenMode, MogyConfig.get().asyncPackScan, MogyConfig.get().asyncPackIcons);
    }

    /** Render thread, end of every client tick. */
    public static void onEndTick(Minecraft client) {
        // 1.16.1 has no "game load finished" hook; startup is done once the loading overlay is gone.
        if (!started && client.getOverlay() == null) {
            started = true;
            onClientStarted(client);
        }
        StallWatchdog.heartbeat();
        // Finished icons are uploaded to the GPU in small batches, so even a menu with
        // hundreds of packs never causes a frame spike.
        PackIconCache.drainUploads(client);
    }

    /** Render thread, once startup loading has finished: preload pack icons, start the freeze recorder. */
    private static void onClientStarted(Minecraft client) {
        PackScanService.onClientStarted(client);
        if (MogyConfig.get().freezeReports) {
            StallWatchdog.start();
        }
    }

    public static void onClientStopping() {
        StallWatchdog.stop();
        PackWorker.shutdown();
    }
}
