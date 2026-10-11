package com.mogy.optimizer.diagnostics;

import com.mogy.optimizer.MogyOptimizerClient;
import com.mogy.optimizer.packs.DirectoryListingCache;
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
 * <p>Cost while the game runs normally: one volatile write per tick and one wake-up of an
 * idle thread every 200 ms.
 */
public final class StallWatchdog {
    private static final long STALL_THRESHOLD_MS = 1500;
    private static final long REPORT_THRESHOLD_MS = 3000;
    private static final long SAMPLE_INTERVAL_MS = 250;
    private static final int MAX_REPORTS_PER_SESSION = 20;
    private static final int TOP_FRAMES = 30;

    private static volatile long lastHeartbeatMs = System.currentTimeMillis();
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

    public static void stop() {
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }

    private static void run() {
        int reports = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(200);
                long stalledFor = System.currentTimeMillis() - lastHeartbeatMs;
                if (stalledFor < STALL_THRESHOLD_MS || !renderThread.isAlive()) {
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
                    writeReport(duration, samples);
                }
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (Throwable t) {
            MogyOptimizerClient.LOGGER.warn("Stall watchdog stopped", t);
        }
    }

    private static void writeReport(long durationMs, List<StackTraceElement[]> samples) {
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
        out.append("=== Freeze of ").append(durationMs).append(" ms at ")
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
