package com.telcobright.billing.ingest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Graceful shutdown of a single-worker consumer executor — the cutover-safe alternative to
 * {@link ExecutorService#shutdownNow()}. The caller first flips its own {@code running=false} flag (so the
 * loop stops taking NEW work), then calls {@link #drain}: no new tasks are accepted and the IN-FLIGHT task is
 * allowed to finish — for the CDR ingest that means the current poll-batch completes its DB write
 * (cdr + cdrerror + acc_chargeable + summary_affected, the tx commit = flush) and then commits its Kafka
 * offsets, BEFORE the process exits. Only when the drain overruns {@code timeout} is the worker interrupted
 * ({@code shutdownNow}); that path is redelivery-safe (at-least-once + per-schema idempotency).
 *
 * <p>This exists because {@code shutdownNow()} on a restart/cutover interrupts a batch mid-write, which at best
 * forces a redelivery and at worst leaves a one-call boundary gap between production and the shadow.</p>
 */
public final class GracefulDrain {
    private GracefulDrain() {}

    /**
     * @param exec    the single-worker executor running the consume loop
     * @param timeout how long to wait for the in-flight task to finish
     * @param unit    time unit of {@code timeout}
     * @param onForce ran once, before {@code shutdownNow()}, ONLY if the drain overruns (e.g. {@code consumer.wakeup()});
     *                may be {@code null}
     * @return {@code true} if the worker drained cleanly within the timeout; {@code false} if it had to be forced
     */
    public static boolean drain(ExecutorService exec, long timeout, TimeUnit unit, Runnable onForce) {
        exec.shutdown();                                  // stop accepting new tasks; in-flight task keeps running
        boolean drained;
        try {
            drained = exec.awaitTermination(timeout, unit);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            if (onForce != null) {
                try { onForce.run(); } catch (RuntimeException ignore) { /* best effort */ }
            }
            exec.shutdownNow();
        }
        return drained;
    }
}
