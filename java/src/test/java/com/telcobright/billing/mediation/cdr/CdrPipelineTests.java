// Faithful port of tests/Billing.Tests/CdrPipelineTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (CdrPipeline) per RULE T0.
package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.summary.ISummaryStore;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.testsupport.TestData;

/**
 * The decoupled CDR processing pipeline over an already-fetched batch: mediate each cdr (detect SG -> rate
 * via the RateCache) -> build + merge summaries -> one write. Calls that match no rate fall to the unrated
 * bucket; same-bucket calls merge onto one summary row.
 */
class CdrPipelineTests {

    // The C# in-memory ISummaryStore fake: records the LoadByStartTimes calls and the emitted SQL.
    private static final class InMemorySummaryStore implements ISummaryStore {
        final List<String> ExecutedSql = new ArrayList<>();
        final List<Load> Loads = new ArrayList<>();

        @Override
        public List<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, Collection<LocalDateTime> startTimes) {
            Loads.add(new Load(table, startTimes.toArray(new LocalDateTime[0])));
            return new ArrayList<>();
        }

        @Override
        public int ExecuteNonQuery(String sql) { ExecutedSql.add(sql); return 1; }
    }

    // Java carrier for the C# named ValueTuple (CdrSummaryType Table, DateTime[] StartTimes).
    private record Load(CdrSummaryType Table, LocalDateTime[] StartTimes) {}

    // SG10 customer rating config: per-minute 1.0 for prefix 1712 (partner 5).
    private static MediationContext Mediation() {
        return MediationContext.ForRating(List.of(
                TestData.Tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7))));
    }

    private static final Map<Integer, Partner> RetailPartner5 = Map.of(5, new Partner(5, null, 3));

    // A retail (SG10) call that is both rate-able (in-partner + called number) and summary-ready.
    private static cdr Call(String called, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1; c.InPartnerId = 5; c.IncomingRoute = "in"; c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = called; c.StartTime = when; c.AnswerTime = when; c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60); c.RoundedDuration = BigDecimal.valueOf(60); c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880"; c.AnsIdTerm = 42; c.MatchedPrefixSupplier = "1712";
        return c;
    }

    // LINQ .Count(pred) / .First(pred) / .Single(pred) helpers.
    private static int count(List<String> list, Predicate<String> p) {
        return (int) list.stream().filter(p).count();
    }

    private static String first(List<String> list, Predicate<String> p) {
        return list.stream().filter(p).findFirst().orElseThrow();
    }

    private static <T> T single(List<T> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    @Test
    void Processes_a_batch_rates_each_and_writes_summaries() {
        var store = new InMemorySummaryStore();
        var when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        var batch = new CdrBatch(Mediation(), RetailPartner5, List.of(
                Call("8801712345678", when),
                Call("8801712000000", when),   // same prefix 1712, same bucket -> merges onto the same row
                Call("8809999999", when)),     // normalizes to 9999999 -> no rate prefix -> unrated
                store);

        var result = CdrPipeline.Default().Process(batch);

        assertEquals(3, result.Total());
        assertEquals(2, result.Rated().size());
        assertEquals(1, result.Errored().size());                              // 8809999999 matched no rate -> cdrerror
        assertEquals("no chargeable produced", result.Errored().get(0).ErrorCode);
        assertEquals(0, new BigDecimal("2.0").compareTo(result.TotalCharged()));       // two 1.0 calls
        for (var r : result.Rated()) assertEquals(10, r.Customer().servicegroup);

        // cdr rows: the 2 qualified cdrs go out as ONE batched insert; the rejected one -> cdrerror.
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into cdr (")));
        assertEquals(2, first(store.ExecutedSql, s -> s.startsWith("insert into cdr (")).split("\\),\\(", -1).length);
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into cdrerror (")));

        // chargeable rows: the 2 customer legs go out as ONE batched insert; each got a new id.
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into acc_chargeable")));
        var chargeableInsert = first(store.ExecutedSql, s -> s.startsWith("insert into acc_chargeable"));
        assertEquals(2, chargeableInsert.split("\\),\\(", -1).length);     // two value tuples
        for (var r : result.Rated()) assertTrue(r.Customer().id > 0);

        // both rated calls fall in the SAME day+hr bucket -> exactly one row written per table.
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into sum_voice_day_03")));
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into sum_voice_hr_03")));
    }

    @Test
    void Prev_summary_loads_every_bucket_the_batch_touches() {
        var store = new InMemorySummaryStore();
        // batch spans TWO hours of the same day — the legacy DatesInvolved/HoursInvolved case.
        var batch = new CdrBatch(Mediation(), RetailPartner5, List.of(
                Call("8801712345678", LocalDateTime.of(2026, 6, 19, 14, 30, 0)),
                Call("8801712000000", LocalDateTime.of(2026, 6, 19, 15, 30, 0))),
                store);

        CdrPipeline.Default().Process(batch);

        // the hr table is loaded ONCE (not per-cdr), with BOTH involved hour buckets (legacy HoursInvolved).
        var hrLoad = single(store.Loads.stream().filter(l -> l.Table() == CdrSummaryType.sum_voice_hr_03).toList());
        assertTrue(Arrays.asList(hrLoad.StartTimes()).contains(LocalDateTime.of(2026, 6, 19, 14, 0, 0)));
        assertTrue(Arrays.asList(hrLoad.StartTimes()).contains(LocalDateTime.of(2026, 6, 19, 15, 0, 0)));
        // the day table is loaded once with the involved date.
        var dayLoad = single(store.Loads.stream().filter(l -> l.Table() == CdrSummaryType.sum_voice_day_03).toList());
        assertTrue(Arrays.asList(dayLoad.StartTimes()).contains(LocalDateTime.of(2026, 6, 19, 0, 0)));
    }

    // a checklist rule that rejects a call with no originating calling number.
    private static final class RequireCallingNumber implements IValidationRule<cdr> {
        @Override public boolean Validate(cdr c) {
            return !(c.OriginatingCallingNumber == null || c.OriginatingCallingNumber.isEmpty());
        }
        @Override public String ValidationMessage() { return "calling number required"; }
    }

    @Test
    void Validation_checklist_gates_the_cdr_and_is_separate_for_answered_vs_failed() {
        var store = new InMemorySummaryStore();
        // SG10's ANSWERED checklist requires a calling number; the unanswered checklist stays empty.
        ServiceGroupConfiguration base = ServiceGroupConfiguration.Defaults.get(10);
        Map<Integer, ServiceGroupConfiguration> configs = Map.of(
                10, new ServiceGroupConfiguration(base.ServiceGroupId(), base.Disabled(), base.Rules(),
                        List.<IValidationRule<cdr>>of(new RequireCallingNumber()), base.UnansweredChecklist()));
        var med = MediationContext.ForRating(List.of(
                TestData.Tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7))),
                null, null, configs, null);

        var when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        var answeredOk = Call("8801712345678", when); answeredOk.OriginatingCallingNumber = "8801999000111";
        var answeredBad = Call("8801712000000", when); answeredBad.OriginatingCallingNumber = null;   // fails answered checklist
        var failedCall = Call("8801712000001", when); failedCall.OriginatingCallingNumber = null; failedCall.ChargingStatus = 0; // unanswered -> answered checklist NOT applied

        var result = CdrPipeline.Default().Process(
                new CdrBatch(med, RetailPartner5, List.of(answeredOk, answeredBad, failedCall), store));

        assertEquals(2, result.Rated().size());        // answeredOk + the unanswered call (separate, empty checklist)
        assertEquals(1, result.Errored().size());      // answeredBad rejected by the answered checklist
        assertEquals("calling number required", result.Errored().get(0).ErrorCode);
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into cdrerror (")));
    }

    @Test
    void Empty_batch_writes_nothing() {
        var store = new InMemorySummaryStore();
        var result = CdrPipeline.Default().Process(
                new CdrBatch(Mediation(), RetailPartner5, List.<cdr>of(), store));

        assertEquals(0, result.Total());
        assertTrue(result.Rated().isEmpty());
        assertTrue(store.ExecutedSql.isEmpty());
    }
}
