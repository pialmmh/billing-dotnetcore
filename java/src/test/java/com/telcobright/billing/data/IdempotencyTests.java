package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.cdr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * B5 — the idempotency's pure parts: which column is a record's key and where it is looked for
 * ({@link IdempotencyKey}), and the drop of a later copy inside ONE batch (the database is not asked for that:
 * two copies in one poll are both "not written yet"). The database's part is proven on each engine's own lab.
 */
class IdempotencyTests {

    private static cdr record(String billId, String callUuid) {
        cdr c = new cdr();
        c.UniqueBillId = billId;
        c.ChannelCallUuid = callUuid;
        return c;
    }

    @Test
    void the_first_copy_of_a_record_in_a_batch_wins_and_the_order_is_kept() {
        cdr first = record("bill-1", "u-1"), other = record("bill-2", "u-2"), again = record("bill-1", "u-1"),
                third = record("bill-1", "u-1");

        List<cdr> kept = MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(List.of(first, other, again, third), IdempotencyKey.UniqueBillId);

        assertEquals(2, kept.size());
        assertSame(first, kept.get(0));
        assertSame(other, kept.get(1));
    }

    @Test
    void a_record_without_a_key_cannot_be_told_from_another_and_is_always_kept() {
        cdr a = record(null, null), b = record("", ""), c = record(null, null);

        assertEquals(3, MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(List.of(a, b, c), IdempotencyKey.UniqueBillId).size());
        assertEquals(3, MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(List.of(a, b, c), IdempotencyKey.ChannelCallUuid).size());
    }

    @Test
    void a_batch_without_copies_is_handed_back_as_it_came() {
        List<cdr> batch = List.of(record("bill-1", "u-1"), record("bill-2", "u-2"));

        assertSame(batch, MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(batch, IdempotencyKey.UniqueBillId));
    }

    @Test
    void the_ratified_key_is_the_call_uuid_looked_for_in_cdr_and_cdrerror() {
        // Two tiers' records of one call share the bill id AND the call uuid; each sits in its own schema, so within
        // one schema the call uuid names one record. Two DIFFERENT calls may share a bill id only by a producer's bug;
        // the ratified key tells them apart.
        cdr one = record("same-bill", "u-1"), two = record("same-bill", "u-2");

        assertEquals("ChannelCallUuid", IdempotencyKey.ChannelCallUuid.Column());
        assertEquals(List.of("cdr", "cdrerror"), IdempotencyKey.ChannelCallUuid.Tables());
        assertEquals(2, MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(List.of(one, two), IdempotencyKey.ChannelCallUuid).size());
        assertEquals(1, MySqlCdrBatchRunner.DropLaterCopiesInTheBatch(List.of(one, two), IdempotencyKey.UniqueBillId).size());
    }

    @Test
    void the_mysql_key_is_the_bill_id_looked_for_in_cdr_only_as_it_always_was() {
        assertEquals("UniqueBillId", IdempotencyKey.UniqueBillId.Column());
        assertEquals(List.of("cdr"), IdempotencyKey.UniqueBillId.Tables());
    }

    @Test
    void a_record_that_came_without_a_call_uuid_takes_its_bill_id_as_one() {
        // The gRPC entries (FinalizeAndSummarize, ProcessCdrBatch) build a cdr with no ChannelCallUuid.
        cdr fromGrpc = record("session-77", null), fromTheWire = record("call-id", "u-9"), keyless = record(null, null);

        for (cdr c : List.of(fromGrpc, fromTheWire, keyless)) IdempotencyKey.ChannelCallUuid.StampOn(c);

        assertEquals("session-77", fromGrpc.ChannelCallUuid);
        assertEquals("u-9", fromTheWire.ChannelCallUuid, "a key the wire sent is never replaced");
        assertNull(keyless.ChannelCallUuid);
    }

    @Test
    void the_mysql_key_stamps_nothing() {
        cdr c = record("bill-1", null);

        IdempotencyKey.UniqueBillId.StampOn(c);

        assertNull(c.ChannelCallUuid);
    }
}
