package com.mogy.optimizer.display;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.config.MogyConfig;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/**
 * Borderless fullscreen: the replacement for vanilla's exclusive fullscreen.
 *
 * <h2>Why exclusive fullscreen breaks screensharing</h2>
 * Vanilla's {@code Window.setMode()} calls {@code glfwSetWindowMonitor(handle, monitor, ...)},
 * which attaches the window to the monitor. On Windows the GPU driver then hands the game
 * direct control of the display ("independent flip"), bypassing the desktop compositor
 * (DWM). Capture tools (Discord, OBS window capture, Windows Graphics Capture) read
 * frames from the compositor, so they must either force composition back on, which
 * causes a mode switch and a hitch, or hook the swap call. The usual results are a
 * frozen or black stream and stutter in the game itself.
 *
 * <h2>The fix</h2>
 * Instead of attaching to the monitor, the window is made undecorated and moved and
 * resized to cover the monitor. It looks identical to fullscreen, but it stays a normal
 * desktop window. The compositor keeps presenting it, capture tools get every frame, and
 * alt-tab is instant because there is no display mode change.
 *
 * <p>Some drivers notice a window that exactly covers a monitor and promote it back to
 * independent flip. The window is therefore made {@code borderlessOverscanPixels} taller
 * than the monitor (one off-screen pixel row by default), which stops that detection.
 *
 * <p>Borderless mode always uses the desktop resolution. Choosing a different
 * fullscreen resolution needs a display mode change, which only exclusive mode can do.
 * Set {@code fullscreenMode} to {@code EXCLUSIVE} in the config if you need that.
 */
public final class BorderlessFullscreen {
    private static Boolean platformSupported;

    private BorderlessFullscreen() {
    }

    /** True when borderless mode is configured and the windowing platform supports it. */
    public static boolean isEnabled() {
        return MogyConfig.get().fullscreenMode == MogyConfig.FullscreenMode.BORDERLESS && isPlatformSupported();
    }

    /**
     * Windows and X11 support positioning an undecorated window over a monitor. macOS uses
     * its own native fullscreen path, and Wayland does not allow clients to position
     * windows, so both keep vanilla behaviour.
     */
    private static boolean isPlatformSupported() {
        if (platformSupported == null) {
            String platform = Window.getPlatform();
            platformSupported = "win32".equals(platform) || "x11".equals(platform);
            if (!platformSupported) {
                MogyOptimizerClient.LOGGER.info("Borderless fullscreen is not supported on platform '{}'; using vanilla fullscreen", platform);
            }
        }
        return platformSupported;
    }

    /** Turns the window into an undecorated window covering the given monitor area. */
    public static void enter(long handle, int x, int y, int width, int height) {
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        // monitor = NULL keeps the window composited. The refresh rate is ignored for windowed mode.
        GLFW.glfwSetWindowMonitor(handle, 0L, x, y, width, height + MogyConfig.get().borderlessOverscanPixels, GLFW.GLFW_DONT_CARE);
    }

    /** Restores the normal title bar and borders before vanilla restores the windowed size. */
    public static void restoreDecorations(long handle) {
        try {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        } catch (Throwable t) {
            MogyOptimizerClient.LOGGER.warn("Could not restore window decorations", t);
        }
    }
}
