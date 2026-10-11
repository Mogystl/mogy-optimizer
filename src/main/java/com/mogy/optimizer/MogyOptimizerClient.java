package com.mogy.optimizer;

import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.diagnostics.StallWatchdog;
import com.mogy.optimizer.packs.PackIconCache;
import com.mogy.optimizer.packs.PackScanService;
import com.mogy.optimizer.packs.PackWorker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for Mogy Optimizer.
 *
 * <p>The mod has two independent modules:
 * <ul>
 *   <li>{@code display}: fixes fullscreen/screenshare stutter. It works entirely through
 *       {@link com.mogy.optimizer.mixin.WindowMixin}, so nothing needs wiring here.</li>
 *   <li>{@code packs}: makes the resource pack menu open instantly and never crash. Its
 *       background worker and the per-tick icon upload are wired up below.</li>
 * </ul>
 */
public final class MogyOptimizerClient implements ClientModInitializer {
    public static final String MOD_ID = "mogy-optimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mogy Optimizer");

    @Override
    public void onInitializeClient() {
        MogyConfig.load();

        // Finished icons are uploaded to the GPU in small batches on the render thread,
        // so even a menu with hundreds of packs never causes a frame spike.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StallWatchdog.heartbeat();
            PackIconCache.drainUploads(client);
        });

        // Preload pack icons; start the freeze recorder once startup loading is over.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            PackScanService.onClientStarted(client);
            if (MogyConfig.get().freezeReports) {
                StallWatchdog.start();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            StallWatchdog.stop();
            PackWorker.shutdown();
        });

        LOGGER.info("Mogy Optimizer initialized (fullscreen={}, asyncPackScan={}, asyncPackIcons={})",
                MogyConfig.get().fullscreenMode, MogyConfig.get().asyncPackScan, MogyConfig.get().asyncPackIcons);
    }
}
