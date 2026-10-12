package com.mogy.optimizer.diagnostics;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.packs.DirectoryListingCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records what the render thread is doing when the game freezes.
 *
 * <p>Some freezes come from code outside this mod (launcher features, other mods), and some
 * launchers hide mod log output, so guessing at the cause is unreliable. This watchdog
 * finds out instead. The render thread bumps a heartbeat every client tick (20 times a
 * second). A low-priority daemon thread checks it five times a second. If the heartbeat
 * stops for more than {@value #STALL_THRESHOLD_MS} ms, the watchdog samples the render
 * thread's stack trace every {@value #SAMPLE_INTERVAL_MS} ms until it resumes. For freezes
 * longer than {@value #REPORT_THRESHOLD_MS} ms, it appends a report to
 * {@code config/mogy-optimizer-stalls.txt}. The report lists the methods that appeared in
 * the most samples (where the time went), plus two full stack traces.
 *
 * <p>It also catches <b>sustained low FPS</b>, which is not a freeze (the game keeps ticking)
 * but feels like one. The render thread bumps a frame counter every frame. If the frame
 * rate stays below {@value #LOW_FPS_THRESHOLD} for two seconds while the game is not being
 * throttled on purpose (minimized, AFK or a low FPS cap), the render thread is sampled for
 * three seconds and a report is written, at most once every 30 seconds.
 *
 * <p>Cost while the game runs normally: one volatile write per tick and per frame, and one
 * wake-up of an idle thread every 200 ms.
 */
public final class StallWatchdog {
    private static final long STALL_THRESHOLD_MS = 1500;
    private static final long REPORT_THRESHOLD_MS = 3000;
    private static final long SAMPLE_INTERVAL_MS = 250;
    private static final int MAX_REPORTS_PER_SESSION = 20;
    private static final int TOP_FRAMES = 30;

    private static final int LOW_FPS_THRESHOLD = 20;
    private static final long LOW_FPS_WINDOW_MS = 2000;
    private static final long LOW_FPS_SAMPLE_MS = 3000;
    private static final long LOW_FPS_SAMPLE_INTERVAL_MS = 50;
    private static final long LOW_FPS_REPORT_COOLDOWN_MS = 30_000;

    private static volatile long lastHeartbeatMs = System.currentTimeMillis();
    private static volatile long frameCount;
    private static Thread renderThread;
    private static Thread watchdogThread;

    private StallWatchdog() {
    }

    /** Must be called on the render thread. */
    public static void start() {
        if (watchdogThread != null) {
            return;
        }
        renderThread = Thread.currentThread();
        heartbeat();
        watchdogThread = new Thread(StallWatchdog::run, "Mogy-StallWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MIN_PRIORITY);
        watchdogThread.start();
    }

    /** Render thread, every client tick. */
    public static void heartbeat() {
        lastHeartbeatMs = System.currentTimeMillis();
    }

    /** Render thread, every frame. */
    @SuppressWarnings("NonAtomicOperationOnVolatileField") // single writer: the render thread
    public static void frame() {
        frameCount++;
    }

    public static void stop() {
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }

    private static void run() {
        int reports = 0;
        long windowStartMs = System.currentTimeMillis();
        long windowStartFrames = frameCount;
        long lastLowFpsReportMs = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(200);
                long now = System.currentTimeMillis();
                long stalledFor = now - lastHeartbeatMs;
                if (!renderThread.isAlive()) {
                    continue;
                }
                if (stalledFor < STALL_THRESHOLD_MS) {
                    // Not frozen: check the frame rate over the last window instead.
                    if (now - windowStartMs >= LOW_FPS_WINDOW_MS) {
                        double fps = (frameCount - windowStartFrames) * 1000.0 / (now - windowStartMs);
                        windowStartMs = now;
                        windowStartFrames = frameCount;
                        if (fps < LOW_FPS_THRESHOLD && !isThrottledOnPurpose() && reports < MAX_REPORTS_PER_SESSION
                                && now - lastLowFpsReportMs >= LOW_FPS_REPORT_COOLDOWN_MS) {
                            if (sampleLowFps(fps)) {
                                lastLowFpsReportMs = now;
                                reports++;
                            }
                            windowStartMs = System.currentTimeMillis();
                            windowStartFrames = frameCount;
                        }
                    }
                    continue;
                }
                long stallStart = lastHeartbeatMs;
                List<StackTraceElement[]> samples = new ArrayList<>();
                while (lastHeartbeatMs == stallStart && !Thread.currentThread().isInterrupted()) {
                    samples.add(renderThread.getStackTrace());
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                }
                long duration = System.currentTimeMillis() - stallStart;
                if (duration >= REPORT_THRESHOLD_MS && reports < MAX_REPORTS_PER_SESSION && !samples.isEmpty()) {
                    reports++;
                    writeReport("Freeze of " + duration + " ms", samples);
                }
                windowStartMs = System.currentTimeMillis();
                windowStartFrames = frameCount;
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (Throwable t) {
            MogyOptimizerClient.LOGGER.warn("Stall watchdog stopped", t);
        }
    }

    /** Samples the render thread for a few seconds while the frame rate is low, then writes a report. */
    private static boolean sampleLowFps(double fps) throws InterruptedException {
        long start = frameCount;
        long startMs = System.currentTimeMillis();
        List<StackTraceElement[]> samples = new ArrayList<>();
        while (System.currentTimeMillis() - startMs < LOW_FPS_SAMPLE_MS) {
            samples.add(renderThread.getStackTrace());
            Thread.sleep(LOW_FPS_SAMPLE_INTERVAL_MS);
        }
        long framesWhileSampling = frameCount - start;
        if (framesWhileSampling == 0) {
            return false; // a full freeze, not low FPS: the freeze report covers it
        }
        double sampledFps = framesWhileSampling * 1000.0 / Math.max(1, System.currentTimeMillis() - startMs);
        writeReport(String.format("Low FPS (%.1f fps, then %.1f fps while sampling) on %s", fps, sampledFps, currentScreenName()), samples);
        return true;
    }

    /** Vanilla lowers the frame rate on purpose when minimized or AFK, or the player set a low cap; that is not a problem. */
    private static boolean isThrottledOnPurpose() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.getWindow().isIconified()
                    || minecraft.getFramerateLimitTracker().getFramerateLimit() < LOW_FPS_THRESHOLD + 10;
        } catch (Throwable t) {
            return true;
        }
    }

    private static String currentScreenName() {
        try {
            Screen screen = Minecraft.getInstance().screen;
            return screen == null ? "in-game (no menu open)" : "screen " + screen.getClass().getName();
        } catch (Throwable t) {
            return "unknown screen";
        }
    }

    private static void writeReport(String title, List<StackTraceElement[]> samples) {
        // Inclusive count: in how many samples each method appears anywhere on the stack.
        Map<String, Integer> counts = new HashMap<>();
        for (StackTraceElement[] stack : samples) {
            Set<String> seen = new HashSet<>();
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName() + "." + frame.getMethodName();
                if (seen.add(name)) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());

        StringBuilder out = new StringBuilder();
        out.append("=== ").append(title).append(" at ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append(" (").append(samples.size()).append(" samples) ===\n")
                .append(DirectoryListingCache.stats()).append('\n');
        out.append("Methods on the render thread's stack (% of samples):\n");
        for (int i = 0; i < Math.min(TOP_FRAMES, top.size()); i++) {
            Map.Entry<String, Integer> entry = top.get(i);
            out.append(String.format("  %3d%%  %s%n", entry.getValue() * 100 / samples.size(), entry.getKey()));
        }
        appendStack(out, "First sample", samples.get(0));
        appendStack(out, "Middle sample", samples.get(samples.size() / 2));
        out.append('\n');

        Path file = FabricLoader.getInstance().getConfigDir().resolve("mogy-optimizer-stalls.txt");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(out.toString());
        } catch (IOException e) {
            MogyOptimizerClient.LOGGER.warn("Could not write stall report", e);
        }
    }

    private static void appendStack(StringBuilder out, String title, StackTraceElement[] stack) {
        out.append(title).append(":\n");
        for (int i = 0; i < Math.min(80, stack.length); i++) {
            out.append("    at ").append(stack[i]).append('\n');
        }
    }
}
