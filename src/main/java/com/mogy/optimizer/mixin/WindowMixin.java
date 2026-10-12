package com.mogy.optimizer.mixin;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.display.BorderlessFullscreen;
import com.mogy.optimizer.display.DisplaySync;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks vanilla's window into the display fixes (see {@link BorderlessFullscreen} and
 * {@link DisplaySync} for the root-cause write-ups).
 *
 * <p>The borderless path mirrors the field bookkeeping in vanilla's {@code setMode()}:
 * it saves the windowed rectangle when leaving windowed mode, updates {@code x/y/width/height},
 * and marks the window resized. Leaving fullscreen is still done by vanilla's own windowed
 * branch, so the restored size and position match vanilla exactly.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow @Final private long window;
    @Shadow @Final private ScreenManager screenManager;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private boolean fullscreen;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    /**
     * Whether the window is currently in borderless fullscreen. GLFW cannot tell us:
     * {@code glfwGetWindowMonitor} returns 0 for a borderless window, exactly as it does
     * for a normal window.
     */
    @Unique
    private boolean mogy$borderless;

    @Shadow
    private void setMode() {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mogy$afterCreate(CallbackInfo ci) {
        DisplaySync.configureWindow(this.window);
        // When the game starts in fullscreen, vanilla creates the window already attached
        // to the monitor (exclusive). Re-run setMode so it switches to borderless right away.
        if (this.fullscreen && BorderlessFullscreen.isEnabled() && GLFW.glfwGetWindowMonitor(this.window) != 0L) {
            this.setMode();
        }
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void mogy$borderlessSetMode(CallbackInfo ci) {
        if (!this.fullscreen || !BorderlessFullscreen.isEnabled()) {
            // Going windowed (or borderless is off): put the title bar back, then let
            // vanilla restore the saved windowed rectangle.
            if (this.mogy$borderless) {
                BorderlessFullscreen.restoreDecorations(this.window);
                this.mogy$borderless = false;
            }
            return;
        }

        Monitor monitor = this.screenManager.findBestMonitor((Window) (Object) this);
        if (monitor == null) {
            return; // vanilla logs a warning and falls back to windowed
        }

        boolean wasExclusive = GLFW.glfwGetWindowMonitor(this.window) != 0L;
        if (!wasExclusive && !this.mogy$borderless) {
            // Same as vanilla: remember the windowed rectangle so leaving fullscreen restores it.
            this.windowedX = this.x;
            this.windowedY = this.y;
            this.windowedWidth = this.width;
            this.windowedHeight = this.height;
        }

        // Borderless uses the monitor's current desktop mode; changing resolution would need exclusive mode.
        VideoMode mode = monitor.getCurrentMode();
        try {
            BorderlessFullscreen.enter(this.window, monitor.getX(), monitor.getY(), mode.getWidth(), mode.getHeight());
        } catch (Throwable t) {
            MogyOptimizerClient.LOGGER.warn("Borderless fullscreen failed, falling back to vanilla fullscreen", t);
            BorderlessFullscreen.restoreDecorations(this.window);
            return; // vanilla's exclusive path runs instead
        }
        this.x = monitor.getX();
        this.y = monitor.getY();
        this.width = mode.getWidth();
        this.height = mode.getHeight();
        this.mogy$borderless = true;
        ci.cancel();
    }

    @Inject(method = "updateVsync", at = @At("TAIL"))
    private void mogy$adaptiveVsync(boolean vsync, CallbackInfo ci) {
        DisplaySync.onVsyncApplied(vsync);
    }
}
