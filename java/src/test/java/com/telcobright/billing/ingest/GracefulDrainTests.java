// Same package as the SUT (GracefulDrain) per RULE T0.
package com.telcobright.billing.ingest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * The cutover-safe drain: an in-flight batch (here a short task) MUST run to completion before shutdown, and
 * onForce/interrupt only fire when the drain overruns its budget.
 */
class GracefulDrainTests {

    @Test
    void Clean_drain_lets_the_in_flight_task_finish() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean forced = new AtomicBoolean(false);
        exec.submit(() -> { try { Thread.sleep(100); finished.set(true); } catch (InterruptedException ignore) {} });

        boolean drained = GracefulDrain.drain(exec, 5, TimeUnit.SECONDS, () -> forced.set(true));

        assertTrue(drained, "a task shorter than the timeout must drain cleanly");
        assertTrue(finished.get(), "the in-flight task must run to completion (not be interrupted)");
        assertFalse(forced.get(), "onForce must NOT run on a clean drain");
        assertTrue(exec.isTerminated());
    }

    @Test
    void Overrun_forces_interrupt_and_runs_onForce() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean forced = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        exec.submit(() -> {
            started.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ie) { interrupted.set(true); }
        });
        started.await(2, TimeUnit.SECONDS);

        boolean drained = GracefulDrain.drain(exec, 200, TimeUnit.MILLISECONDS, () -> forced.set(true));

        assertFalse(drained, "a task longer than the timeout must NOT drain cleanly");
        assertTrue(forced.get(), "onForce (e.g. consumer.wakeup) must run when the drain overruns");
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS), "shutdownNow must then terminate the worker");
        assertTrue(interrupted.get(), "the overrunning worker must have been interrupted");
    }

    @Test
    void Null_onForce_is_safe_on_overrun() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> { try { Thread.sleep(10_000); } catch (InterruptedException ignore) {} });

        assertFalse(GracefulDrain.drain(exec, 100, TimeUnit.MILLISECONDS, null));
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));
    }
}
