package com.mogy.optimizer.display;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.config.MogyConfig;
import org.lwjgl.glfw.GLFW;

/**
 * Window and swap-chain settings that keep frame pacing smooth while the game is captured.
 *
 * <h2>Auto-iconify</h2>
 * Vanilla never sets {@code GLFW_AUTO_ICONIFY}, so it stays at GLFW's default of
 * {@code true}. A fullscreen window then minimizes itself the moment it loses focus,
 * for example when you click Discord or OBS on a second monitor. Vanilla's
 * {@code FramerateLimitTracker} sees {@code Window.isIconified()} and caps the game at
 * 10 FPS ({@code ICONIFIED_WINDOW_LIMIT}), and the stream shows a frozen, minimized game.
 * Turning auto-iconify off keeps the window on screen and running at full speed. A real
 * minimize (Win+D, the taskbar) still iconifies and throttles as before.
 *
 * <h2>Adaptive vsync</h2>
 * With vsync on, vanilla uses swap interval 1. Under desktop composition (which capture
 * forces), a frame that misses a refresh waits for the next one, so 60 FPS collapses to
 * 30 and pacing alternates between 16 ms and 33 ms. That alternation is the
 * "micro-stutter". Swap interval -1 ({@code EXT_swap_control_tear}) presents a late
 * frame right away with a small tear instead of waiting a whole refresh. It is only
 * enabled when the driver advertises support.
 */
public final class DisplaySync {
    private static Boolean tearControlSupported;

    private DisplaySync() {
    }

    /** Called once right after vanilla creates the GLFW window. */
    public static void configureWindow(long handle) {
        if (!MogyConfig.get().disableAutoIconify) {
            return;
        }
        try {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
        } catch (Throwable t) {
            MogyOptimizerClient.LOGGER.warn("Could not disable GLFW auto-iconify", t);
        }
    }

    /**
     * Called after vanilla applied the vsync option (render thread, GL context current).
     * Vanilla has already set swap interval 0 or 1; this only upgrades 1 to adaptive.
     */
    public static void onVsyncApplied(boolean vsync) {
        if (!vsync || !MogyConfig.get().adaptiveVsync || !isTearControlSupported()) {
            return;
        }
        GLFW.glfwSwapInterval(-1);
    }

    private static boolean isTearControlSupported() {
        if (tearControlSupported == null) {
            boolean supported;
            try {
                supported = GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear")
                        || GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear");
            } catch (Throwable t) {
                supported = false;
            }
            tearControlSupported = supported;
            MogyOptimizerClient.LOGGER.info("Adaptive vsync (swap_control_tear) {}",
                    supported ? "available" : "not supported by this driver; using normal vsync");
        }
        return tearControlSupported;
    }
}
