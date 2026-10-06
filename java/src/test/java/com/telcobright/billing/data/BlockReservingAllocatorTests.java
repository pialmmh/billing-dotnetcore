package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The correctness case for {@link BlockReservingAutoIncrementManager}: the ONE rule that matters is that no
 * two writers may ever be handed the same {@code acc_chargeable.id}, and {@code acc_chargeable} cannot have a
 * {@code UNIQUE(id)} backstop (MySQL forbids a unique key that omits the partitioning column), so the
 * allocator is the only guarantee.
 *
 * <p>Everything runs against {@link FakeCounterStore}, an in-memory stand-in for the shared
 * {@code autoincrementcounter} row with the SAME atomicity the real {@code update ... set value =
 * last_insert_id(value + n)} statement has. That lets the two-instance and concurrency cases — the ones a
 * database test cannot make deterministic — be asserted exactly.</p>
 */
class BlockReservingAllocatorTests {

    /**
     * An in-memory {@code autoincrementcounter}. {@code reserve} is synchronized on the store, which is
     * precisely the guarantee MySQL gives through the row's exclusive lock inside a single UPDATE statement:
     * concurrent reservers serialise and read back disjoint ranges.
     */
    static final class FakeCounterStore {
        private final Map<String, Long> _value = new HashMap<>();
        final AtomicInteger reservations = new AtomicInteger();
        volatile boolean fail;

        synchronized void seedAtLeast(String table, long floor) {
            _value.merge(table, floor, Math::max);          // greatest(value, floor) - never moves down
        }

        synchronized long value(String table) {
            return _value.getOrDefault(table, 0L);
        }

        synchronized long reserve(String table, int count) throws SQLException {
            if (fail) throw new SQLException("counter unavailable");
            if (!_value.containsKey(table)) throw new SQLException("no counter row for " + table);
            reservations.incrementAndGet();
            long newValue = _value.get(table) + count;
            _value.put(table, newValue);
            return newValue - count + 1;                    // first id of the block
        }

        /** A reserver bound to this store - one per simulated billing instance. */
        IIdBlockReserver reserver() {
            return this::reserve;
        }
    }

    private static List<Long> take(BlockReservingAutoIncrementManager m, String table, int n) {
        var out = new ArrayList<Long>(n);
        for (int i = 0; i < n; i++) out.add(m.GetNewCounter(table));
        return out;
    }

    // -- seeding ------------------------------------------------------------------------------------

    @Test
    @DisplayName("first startup seeds above the existing maximum and issues the next free id")
    void FirstStartupSeedsCorrectly() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 9_137_014L);        // the live PBX max(id) on 2026-09-23
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 100);

        assertEquals(9_137_015L, m.GetNewCounter("acc_chargeable"),
                "the first id issued must be max(id)+1, never a reuse");
    }

    @Test
    @DisplayName("empty table seeds from zero and starts at 1")
    void EmptyTableStartsAtOne() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 100);

        assertEquals(1L, m.GetNewCounter("acc_chargeable"));
        assertEquals(2L, m.GetNewCounter("acc_chargeable"));
    }

    @Test
    @DisplayName("a STALE counter is repaired by seeding, not trusted (legacy left it 6.78M low)")
    void StaleCounterIsRaisedNeverLowered() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 2_355_708L);        // what the retired legacy engine left behind
        store.seedAtLeast("acc_chargeable", 9_137_014L);        // startup seed = greatest(value, max(id))
        assertEquals(9_137_014L, store.value("acc_chargeable"));

        store.seedAtLeast("acc_chargeable", 1L);                // a later, lower floor must NOT move it down
        assertEquals(9_137_014L, store.value("acc_chargeable"),
                "greatest() must make the counter monotonic, or a restart could drag it under a live block");
    }

    // -- the point of the change --------------------------------------------------------------------

    @Test
    @DisplayName("sequential batches hit the counter only when a block runs out - never per batch")
    void SequentialBatchesDoNotReseed() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 10_000);

        // 30 batches x 1,000 chargeables (500 cdrs x 2 legs) = 30,000 ids
        for (int batch = 0; batch < 30; batch++) take(m, "acc_chargeable", 1_000);

        assertEquals(3, store.reservations.get(),
                "30,000 ids out of 10,000-id blocks must cost exactly 3 round trips, not 30");
    }

    @Test
    @DisplayName("ids are contiguous and strictly increasing across a block boundary")
    void BlockBoundaryIsSeamless() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 10);

        var ids = take(m, "acc_chargeable", 25);
        for (int i = 0; i < ids.size(); i++) assertEquals(i + 1L, ids.get(i));
        assertEquals(3, store.reservations.get());
    }

    @Test
    @DisplayName("multiple chargeables per CDR all get distinct ids")
    void MultipleLegsPerCdrGetDistinctIds() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 500L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 64);

        var ids = new HashSet<Long>();
        for (int c = 0; c < 500; c++) {               // customer leg + supplier leg
            ids.add(m.GetNewCounter("acc_chargeable"));
            ids.add(m.GetNewCounter("acc_chargeable"));
        }
        assertEquals(1000, ids.size(), "every leg of every cdr must get its own id");
    }

    // -- failure modes ------------------------------------------------------------------------------

    @Test
    @DisplayName("a rolled-back batch BURNS its ids and never reissues them")
    void RollbackBurnsIdsRatherThanReusingThem() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 10_000);

        var rolledBack = take(m, "acc_chargeable", 1_000);   // this batch throws and rolls back
        var next = take(m, "acc_chargeable", 1_000);         // the redelivered batch

        assertEquals(1_000L, rolledBack.get(999));
        assertEquals(1_001L, next.get(0),
                "the retry must move FORWARD; reusing a burned id is the one thing we cannot do");
        assertTrue(Collections.disjoint(new HashSet<>(rolledBack), new HashSet<>(next)));
    }

    @Test
    @DisplayName("process crash: the unused tail of the block is burned, the restart never goes backwards")
    void CrashBurnsTheRestOfTheBlock() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);

        var before = new BlockReservingAutoIncrementManager(store.reserver(), 10_000);
        var last = 0L;
        for (int i = 0; i < 1_500; i++) last = before.GetNewCounter("acc_chargeable");
        assertEquals(1_500L, last);                          // 8,500 ids of the block still unissued

        // crash: the in-memory block is lost. Restart re-seeds from greatest(counter, max(id)); max(id) is
        // 1,500 because only the issued ids were ever written, but the counter is already at 10,000.
        store.seedAtLeast("acc_chargeable", 1_500L);
        var after = new BlockReservingAutoIncrementManager(store.reserver(), 10_000);

        assertEquals(10_001L, after.GetNewCounter("acc_chargeable"),
                "restart must resume above the whole reserved block, not above max(id)");
    }

    @Test
    @DisplayName("a reservation failure fails the batch - it never guesses an id")
    void ReservationFailureIsFatalToTheBatch() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 10);

        store.fail = true;
        var ex = assertThrows(RuntimeException.class, () -> m.GetNewCounter("acc_chargeable"));
        assertTrue(ex.getMessage().contains("acc_chargeable"));
    }

    @Test
    @DisplayName("an external row inserted ABOVE the cached block is picked up on the next seed")
    void ExternalWriterIsPickedUpAtNextSeed() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 1_000L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 100);
        assertEquals(1_001L, m.GetNewCounter("acc_chargeable"));

        // something outside the allocator writes id 5,000,000 directly.
        store.seedAtLeast("acc_chargeable", 5_000_000L);      // what a restart's greatest(value, max(id)) does
        var restarted = new BlockReservingAutoIncrementManager(store.reserver(), 100);

        assertEquals(5_000_001L, restarted.GetNewCounter("acc_chargeable"),
                "seeding with greatest(value, max(id)) is what recovers from a foreign writer");
    }

    // -- multi-instance: the whole reason for the design ---------------------------------------------

    @Test
    @DisplayName("TWO billing instances sharing one counter never collide")
    void TwoInstancesNeverCollide() {
        var store = new FakeCounterStore();                   // ONE shared counter row
        store.seedAtLeast("acc_chargeable", 0L);
        var a = new BlockReservingAutoIncrementManager(store.reserver(), 10);
        var b = new BlockReservingAutoIncrementManager(store.reserver(), 10);

        var ids = new ArrayList<Long>();
        for (int i = 0; i < 50; i++) {                        // interleaved, as two instances would be
            ids.add(a.GetNewCounter("acc_chargeable"));
            ids.add(b.GetNewCounter("acc_chargeable"));
        }
        assertEquals(100, new HashSet<>(ids).size(), "two instances must never be handed the same id");
    }

    @Test
    @DisplayName("a process-local cached counter WOULD collide - what GET_LOCK does not prevent")
    void CachedAllocatorCollidesProvingWhyBlocksAreNeeded() {
        // Both instances seed from the same max(id) before either writes - exactly the scenario the batch
        // lock cannot help with, because the lock is released between batches while the cache survives.
        var a = new com.telcobright.billing.mediation.sql.CountingAutoIncrementManager(1_000);
        var b = new com.telcobright.billing.mediation.sql.CountingAutoIncrementManager(1_000);

        assertEquals(a.GetNewCounter("acc_chargeable"), b.GetNewCounter("acc_chargeable"),
                "a cached seed hands the SAME id to both instances - with no UNIQUE(id) this is silent");
    }

    @Test
    @DisplayName("concurrent threads on one allocator never repeat an id")
    void ConcurrentThreadsNeverRepeat() throws Exception {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 97);   // prime, to cross boundaries

        int threads = 8, per = 2_000;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var all = Collections.synchronizedList(new ArrayList<Long>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                var mine = new ArrayList<Long>(per);
                for (int i = 0; i < per; i++) mine.add(m.GetNewCounter("acc_chargeable"));
                all.addAll(mine);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "allocator deadlocked");

        assertEquals(threads * per, all.size());
        assertEquals(threads * per, new HashSet<>(all).size(), "a concurrent allocation repeated an id");
    }

    @Test
    @DisplayName("two instances allocating concurrently never overlap")
    void TwoInstancesConcurrentlyNeverOverlap() throws Exception {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 0L);
        var a = new BlockReservingAutoIncrementManager(store.reserver(), 13);
        var b = new BlockReservingAutoIncrementManager(store.reserver(), 13);

        int per = 5_000;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var out = Collections.synchronizedList(new ArrayList<Long>());
        for (var m : List.of(a, b)) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < per; i++) out.add(m.GetNewCounter("acc_chargeable"));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(2 * per, out.size());
        assertEquals(2 * per, new HashSet<>(out).size(), "two instances overlapped under concurrency");
    }

    // -- tenant isolation ----------------------------------------------------------------------------

    @Test
    @DisplayName("each schema draws from its OWN counter; tenants never share an id space")
    void TenantsAreIsolated() {
        var pbx = new FakeCounterStore();
        var hcc = new FakeCounterStore();
        pbx.seedAtLeast("acc_chargeable", 9_137_014L);
        hcc.seedAtLeast("acc_chargeable", 3L);

        var pbxIds = new BlockReservingAutoIncrementManager(pbx.reserver(), 100);
        var hccIds = new BlockReservingAutoIncrementManager(hcc.reserver(), 100);

        assertEquals(9_137_015L, pbxIds.GetNewCounter("acc_chargeable"));
        assertEquals(4L, hccIds.GetNewCounter("acc_chargeable"),
                "HCC must continue its own sequence, not inherit the PBX high-water mark");
    }

    @Test
    @DisplayName("different tables inside one schema keep independent sequences")
    void TablesAreIndependent() {
        var store = new FakeCounterStore();
        store.seedAtLeast("acc_chargeable", 100L);
        store.seedAtLeast("acc_transaction", 7L);
        var m = new BlockReservingAutoIncrementManager(store.reserver(), 50);

        assertEquals(101L, m.GetNewCounter("acc_chargeable"));
        assertEquals(8L, m.GetNewCounter("acc_transaction"));
        assertEquals(102L, m.GetNewCounter("acc_chargeable"));
    }
}
