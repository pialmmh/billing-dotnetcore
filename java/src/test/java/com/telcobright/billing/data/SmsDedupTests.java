package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.testsupport.SmsTestData;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outgoing-SMS batch filter: in-batch duplicates, Kafka redelivery, legacy ownership. */
class SmsDedupTests {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 5, 11, 15, 51);

    private static cdr Sms(String idCall, LocalDateTime start) {
        return SmsTestData.Sms(SmsTestData.MaskSender, SmsTestData.Called, SmsTestData.PartnerA, 60, idCall, start);
    }

    private static List<String> Ids(List<cdr> cdrs) {
        return cdrs.stream().map(c -> c.UniqueBillId).toList();
    }

    @Test
    void a_redelivered_sms_already_in_cdr_or_cdrerror_is_dropped() throws SQLException {
        var kept = MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("11", T), Sms("12", T), Sms("13", T)), false,
                (bills, seqs, checkSeqs, from, to) -> new MySqlCdrBatchRunner.SmsSeen(Set.of("11", "13"), Set.of()));
        assertEquals(List.of("12"), Ids(kept));
    }

    @Test
    void legacy_ownership_drops_an_sms_whose_sequence_number_legacy_already_holds() throws SQLException {
        var lookups = new ArrayList<Boolean>();
        var kept = MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("21", T), Sms("22", T)), true,
                (bills, seqs, checkSeqs, from, to) -> {
                    lookups.add(checkSeqs);
                    return new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of(22L));   // legacy row: SequenceNumber=idCall
                });
        assertEquals(List.of("21"), Ids(kept));
        assertEquals(List.of(true), lookups, "SequenceNumber is checked when legacy-dedup is on");
    }

    @Test
    void with_legacy_dedup_off_only_the_redelivery_check_applies() throws SQLException {
        var kept = MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("31", T)), false,
                (bills, seqs, checkSeqs, from, to) -> {
                    assertFalse(checkSeqs);
                    return new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of(31L));
                });
        assertEquals(List.of("31"), Ids(kept));
    }

    @Test
    void a_duplicate_idCall_inside_one_poll_is_kept_once_instead_of_failing_the_batch() throws SQLException {
        var kept = MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("41", T), Sms("41", T), Sms("42", T)), true,
                (bills, seqs, checkSeqs, from, to) -> new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of()));
        assertEquals(List.of("41", "42"), Ids(kept));
    }

    @Test
    void lookups_are_windowed_on_StartTime_for_partition_pruning() throws SQLException {
        var window = new LocalDateTime[2];
        MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("51", T), Sms("52", T.plusHours(3))), true,
                (bills, seqs, checkSeqs, from, to) -> {
                    window[0] = from; window[1] = to;
                    return new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of());
                });
        assertEquals(T.minusDays(1), window[0]);
        assertEquals(T.plusHours(3).plusDays(1), window[1]);
    }

    @Test
    void a_lookup_failure_aborts_the_batch_never_a_silent_bill() {
        assertThrows(SQLException.class, () -> MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("61", T)), true,
                (bills, seqs, checkSeqs, from, to) -> { throw new SQLException("db down"); }));
    }

    @Test
    void an_sms_without_idCall_cannot_reach_the_batch() {
        var c = Sms("71", T);
        c.UniqueBillId = null;
        assertThrows(IllegalStateException.class, () -> MySqlCdrBatchRunner.FilterSmsSeen(List.of(c), true,
                (bills, seqs, checkSeqs, from, to) -> new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of())));
    }

    @Test
    void the_redelivery_check_reads_UniqueBillId_from_both_tables() throws SQLException {
        var asked = new ArrayList<Set<String>>();
        MySqlCdrBatchRunner.FilterSmsSeen(List.of(Sms("81", T)), true, (bills, seqs, checkSeqs, from, to) -> {
            asked.add(bills);
            assertTrue(seqs.contains(81L));
            return new MySqlCdrBatchRunner.SmsSeen(Set.of(), Set.of());
        });
        assertEquals(List.of(Set.of("81")), asked);
    }
}
