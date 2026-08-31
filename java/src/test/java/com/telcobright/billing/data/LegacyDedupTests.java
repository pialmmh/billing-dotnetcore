// Same package as the SUT (MySqlCdrBatchRunner) — FilterLegacyOwned/PartitionUnowned are package-private.
package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * The CUTOVER SequenceNumber legacy-ownership dedup: keep only cdrs whose SequenceNumber is owned by NEITHER
 * legacy cdr NOR cdrerror. The DB lookup is injected ({@link MySqlCdrBatchRunner.SeqOwnershipLookup}) so the
 * full decision matrix + the fail-safe contract are covered without a database. (The cdr-vs-cdrerror SQL and
 * production-scale performance are validated separately against the shadow DB.)
 */
class LegacyDedupTests {

    private static cdr withSeq(long seq) {
        cdr c = new cdr();
        c.SequenceNumber = seq;
        c.UniqueBillId = "u-" + seq;
        return c;
    }
    private static List<Long> seqs(List<cdr> cdrs) {
        var out = new ArrayList<Long>();
        for (cdr c : cdrs) out.add(c.SequenceNumber);
        return out;
    }

    // ---- matrix: legacy-owned → skipped; unowned → kept ----

    @Test
    void Seq_owned_by_legacy_is_skipped() throws SQLException {
        var batch = List.of(withSeq(1001), withSeq(1002));
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of(1001L)); // legacy owns 1001 (cdr or cdrerror)
        assertEquals(List.of(1002L), seqs(kept), "seq owned by legacy must be dropped, the rest processed");
    }

    @Test
    void Seq_in_neither_table_is_billed() throws SQLException {
        var batch = List.of(withSeq(2001), withSeq(2002));
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of()); // legacy owns none
        assertEquals(List.of(2001L, 2002L), seqs(kept), "a call in neither cdr nor cdrerror is new-owned → processed");
    }

    @Test
    void Mixed_batch_splits_correctly() throws SQLException {
        var batch = List.of(withSeq(1001), withSeq(1002), withSeq(1003), withSeq(1004));
        // legacy owns 1001 (cdr) + 1004 (cdrerror); 1002/1003 are new
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of(1001L, 1004L));
        assertEquals(List.of(1002L, 1003L), seqs(kept));
    }

    @Test
    void Delayed_pre_cutover_event_is_skipped_new_post_cutover_is_billed() throws SQLException {
        // 900 = a delayed pre-cutover call legacy already billed; 9999 = a genuinely new post-cutover call
        var batch = List.of(withSeq(900), withSeq(9999));
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of(900L));
        assertEquals(List.of(9999L), seqs(kept));
    }

    // ---- duplicate Kafka delivery ----

    @Test
    void Duplicate_kafka_event_for_owned_seq_is_fully_dropped() throws SQLException {
        var batch = List.of(withSeq(1001), withSeq(1001)); // same call redelivered in one batch
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of(1001L));
        assertTrue(kept.isEmpty(), "both copies of an owned seq are dropped → no double bill");
    }

    @Test
    void Duplicate_unowned_seq_is_left_for_the_downstream_idempotency_guard() throws SQLException {
        var batch = List.of(withSeq(3000), withSeq(3000)); // new call, delivered twice
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> Set.of());
        assertEquals(2, kept.size(), "legacy-dedup does not dedup NEW calls; the batch/UniqueBillId guard does");
    }

    // ---- fail-safe ----

    @Test
    void Lookup_failure_propagates_so_the_batch_aborts_and_nothing_is_billed() {
        var batch = List.of(withSeq(1001), withSeq(1002));
        SQLException boom = assertThrows(SQLException.class, () ->
                MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> { throw new SQLException("db down"); }));
        assertEquals("db down", boom.getMessage());
        // (propagation → the caller's tx rolls back and the poll-batch is retried; never a silent bill.)
    }

    // ---- batching: ONE lookup for the whole batch, on the DISTINCT seqs ----

    @Test
    void Lookup_is_batched_once_with_distinct_seqs() throws SQLException {
        var batch = List.of(withSeq(10), withSeq(11), withSeq(10), withSeq(12)); // 10 repeated
        var calls = new AtomicInteger();
        MySqlCdrBatchRunner.FilterLegacyOwned(batch, cands -> {
            calls.incrementAndGet();
            assertEquals(Set.of(10L, 11L, 12L), cands, "one call with the DISTINCT candidate seqs, not per-record");
            return Set.of();
        });
        assertEquals(1, calls.get(), "exactly one lookup per batch");
    }

    @Test
    void Empty_batch_does_not_call_the_lookup() throws SQLException {
        var calls = new AtomicInteger();
        var kept = MySqlCdrBatchRunner.FilterLegacyOwned(List.of(), cands -> { calls.incrementAndGet(); return Set.of(); });
        assertTrue(kept.isEmpty());
        assertEquals(0, calls.get());
    }

    // ---- pure partition helper ----

    @Test
    void PartitionUnowned_keeps_all_when_owned_set_empty() {
        var batch = List.of(withSeq(1), withSeq(2));
        assertEquals(2, MySqlCdrBatchRunner.PartitionUnowned(batch, Set.of()).size());
        assertEquals(2, MySqlCdrBatchRunner.PartitionUnowned(batch, null).size());
    }

    @Test
    void PartitionUnowned_keeps_seq_le_zero_it_cannot_be_matched() {
        var batch = List.of(withSeq(0), withSeq(-5), withSeq(1001));
        var kept = MySqlCdrBatchRunner.PartitionUnowned(batch, Set.of(1001L));
        assertEquals(List.of(0L, -5L), seqs(kept), "seq<=0 can't match legacy → kept; 1001 owned → dropped");
    }
}
