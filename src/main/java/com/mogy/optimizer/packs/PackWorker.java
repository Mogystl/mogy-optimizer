package com.mogy.optimizer.packs;

import com.mogy.optimizer.MogyOptimizerClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single background thread that does all resource pack I/O (folder scans, zip
 * opening, PNG decoding) so the render thread never blocks on disk.
 *
 * <p>Using one thread is deliberate. Pack work is I/O-bound, so parallelism gains little
 * on a typical disk. A single queue also gives a natural order: a folder rescan and the
 * icons it invalidates run in sequence without locking. The thread is a low-priority
 * daemon, so it never competes with rendering and never keeps the JVM alive on exit.
 * Every task is wrapped in a {@link Throwable} guard, so one bad pack can never kill the
 * worker or reach the render thread as a crash.
 */
public final class PackWorker {
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "Mogy-PackWorker");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                return thread;
            });

    private PackWorker() {
    }

    /** Queues a task. Returns false if the worker is shut down (the caller should fall back). */
    public static boolean submit(Runnable task) {
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    MogyOptimizerClient.LOGGER.error("Resource pack background task failed", t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
