package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.summary.ISummaryStore;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OUTBOX summary path: in {@link SummaryMode#Outbox} the batch writes ONE compressed
 * {@code summary_affected} row (the rated cdrs + their customer chargeable) INSTEAD of rolling up + writing
 * sum_voice_* inline — and it does NOT pre-load any summary bucket. The blob round-trips (the summary-service
 * will base64→gunzip→JSON it back). The cdr + chargeable writes are unchanged, so it's all one transaction.
 *
 * <p>Faithful-port note: the C# {@code CdrBatch} record had {@code Ids = null, SegmentSize = Default,
 * Summary = Inline} as defaults, so {@code new CdrBatch(..., Summary: SummaryMode.Outbox)} skipped the two
 * middle args. The ported {@code CdrBatch} has no such trailing-default constructor, so the Outbox cases call
 * the canonical 7-arg constructor with {@code null} Ids + {@code BatchSqlWriter.DefaultSegmentSize}.</p>
 */
class SummaryOutboxWriterTests {

    private static final class InMemorySummaryStore implements ISummaryStore {
        final List<String> ExecutedSql = new ArrayList<>();
        final List<CdrSummaryType> Loads = new ArrayList<>();

        @Override
        public List<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, Collection<LocalDateTime> startTimes) {
            Loads.add(table);
            return new ArrayList<>();
        }

        @Override
        public int ExecuteNonQuery(String sql) {
            ExecutedSql.add(sql);
            return 1;
        }
    }

    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    private static final Map<Integer, Partner> RetailPartner5 =
            Map.of(5, new Partner(5, "", 3));

    private static cdr Call(String called, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1;
        c.InPartnerId = 5;
        c.IncomingRoute = "in";
        c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1";
        c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = called;
        c.StartTime = when;
        c.AnswerTime = when;
        c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60);
        c.RoundedDuration = BigDecimal.valueOf(60);
        c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880";
        c.AnsIdTerm = 42;
        c.MatchedPrefixSupplier = "1712";
        return c;
    }

    private static String ExtractBase64(String outboxInsert) {
        final String marker = "values ('cdr', '";
        int start = outboxInsert.indexOf(marker) + marker.length();
        return outboxInsert.substring(start, outboxInsert.length() - 2);   // strip trailing ')
    }

    @Test
    void Outbox_mode_writes_one_summary_affected_row_and_skips_inline_summaries() {
        InMemorySummaryStore store = new InMemorySummaryStore();
        LocalDateTime when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        CdrBatch batch = new CdrBatch(Mediation(), RetailPartner5, List.of(
                Call("8801712345678", when),
                Call("8801712000000", when)
        ), store, null, BatchSqlWriter.DefaultSegmentSize, SummaryMode.Outbox);

        CdrBatchResult result = CdrPipeline.Default().Process(batch);

        assertEquals(2, result.Rated().size());
        // cdr + chargeable writes still happen (same transaction).
        assertEquals(1, (int) store.ExecutedSql.stream().filter(s -> s.startsWith("insert into cdr (")).count());
        assertEquals(1, (int) store.ExecutedSql.stream().filter(s -> s.startsWith("insert into acc_chargeable")).count());
        // EXACTLY one outbox row; NO inline sum_voice writes; NO bucket pre-load.
        assertEquals(1, (int) store.ExecutedSql.stream().filter(s -> s.startsWith("insert into summary_affected")).count());
        assertTrue(store.ExecutedSql.stream().noneMatch(s -> s.contains("sum_voice_")));
        assertTrue(store.Loads.isEmpty());
    }

    @Test
    void Inline_mode_is_unchanged_and_writes_no_outbox_row() {
        InMemorySummaryStore store = new InMemorySummaryStore();
        LocalDateTime when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        // default Summary = Inline
        CdrBatch batch = new CdrBatch(Mediation(), RetailPartner5, List.of(Call("8801712345678", when)), store);

        CdrPipeline.Default().Process(batch);

        assertEquals(1, (int) store.ExecutedSql.stream().filter(s -> s.startsWith("insert into sum_voice_day_03")).count());
        assertTrue(store.ExecutedSql.stream().noneMatch(s -> s.startsWith("insert into summary_affected")));
    }

    @Test
    void Outbox_blob_round_trips_the_cdrs_and_their_customer_chargeable() {
        InMemorySummaryStore store = new InMemorySummaryStore();
        LocalDateTime when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        CdrBatch batch = new CdrBatch(Mediation(), RetailPartner5, List.of(
                Call("8801712345678", when),
                Call("8801712000000", when)
        ), store, null, BatchSqlWriter.DefaultSegmentSize, SummaryMode.Outbox);

        CdrPipeline.Default().Process(batch);

        // C# store.ExecutedSql.Single(...) — exactly one outbox row.
        List<String> outboxRows = store.ExecutedSql.stream()
                .filter(s -> s.startsWith("insert into summary_affected")).toList();
        assertEquals(1, outboxRows.size());
        List<Entry> decoded = SummaryOutboxWriter.Decode(ExtractBase64(outboxRows.get(0)));

        assertEquals(2, decoded.size());
        assertEquals("8801712345678", decoded.get(0).Cdr().TerminatingCalledNumber);
        assertEquals(1, decoded.get(0).Cdr().SwitchId);
        assertEquals(0, new BigDecimal("60").compareTo(decoded.get(0).Cdr().DurationSec));
        assertEquals(when, decoded.get(0).Cdr().StartTime);
        // the customer-leg chargeable rode along (servicegroup + billed amount the summary builder reads).
        assertNotNull(decoded.get(0).Customer());
        assertEquals(10, decoded.get(0).Customer().servicegroup);
        assertEquals((byte) AssignmentDirection.Customer.value, decoded.get(0).Customer().assignedDirection);
    }

    @Test
    void Encode_decode_is_a_pure_round_trip() {
        cdr cdr = Call("8801712345678", LocalDateTime.of(2026, 6, 19, 14, 30, 0));
        cdr.UniqueBillId = "bill-1";
        acc_chargeable chargeable = new acc_chargeable();
        chargeable.servicegroup = 10;
        chargeable.BilledAmount = new BigDecimal("1.5");
        chargeable.assignedDirection = (byte) AssignmentDirection.Customer.value;
        chargeable.Prefix = "1712";
        List<RatedCdr> rated = List.of(new RatedCdr(cdr, List.of(chargeable)));

        List<Entry> decoded = SummaryOutboxWriter.Decode(SummaryOutboxWriter.Encode(rated));

        assertEquals(1, decoded.size());
        assertEquals("bill-1", decoded.get(0).Cdr().UniqueBillId);
        assertEquals(0, new BigDecimal("1.5").compareTo(decoded.get(0).Customer().BilledAmount));
        assertEquals("1712", decoded.get(0).Customer().Prefix);
    }
}
