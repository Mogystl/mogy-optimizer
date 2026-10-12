package com.mogy.optimizer.packs;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What {@link PackScanService} needs from a resource pack screen. Implemented by
 * {@link com.mogy.optimizer.mixin.PackSelectionScreenMixin}, so the service never touches
 * the screen's private fields directly.
 */
public interface AsyncPackScreen {
    PackRepository<?> mogy$repository();

    Path mogy$packDir();

    /** Bumped on every scan request and on close. A scan result is applied only if its generation is still current. */
    AtomicInteger mogy$scanGeneration();

    boolean mogy$isClosed();

    /**
     * Render thread only. Installs a finished scan result, or, when {@code available} is
     * null, runs vanilla's synchronous reload as a fallback.
     */
    void mogy$applyScan(Map<String, Pack> available);
}
