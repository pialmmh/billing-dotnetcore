// Faithful port of tests/Billing.Tests/CdrPipelineTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (CdrPipeline) per RULE T0.
package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.testsupport.TestData;

/**
 * The decoupled CDR processing pipeline over an already-fetched batch: mediate each cdr (detect SG -> rate
 * via the RateCache) -> qualify -> one write. Calls that match no rate fall to the cdrerror bucket. Summaries
 * are OUTBOX-only: the batch emits ONE {@code summary_affected} row (atomic with the cdr/chargeable write)
 * for the standalone summary-service to roll up — no inline sum_voice_* write.
 */
class CdrPipelineTests {

    // The in-memory tx executor fake: records the emitted SQL (ISqlExecutor is now just an ISqlExecutor seam).
    private static final class InMemorySqlExecutor implements ISqlExecutor {
        final List<String> ExecutedSql = new ArrayList<>();

        @Override
        public int ExecuteNonQuery(String sql) { ExecutedSql.add(sql); return 1; }
    }

    // SG10 customer rating config: per-minute 1.0 for prefix 1712 (partner 5).
    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    private static final Map<Integer, Partner> RetailPartner5 = Map.of(5, new Partner(5, null, 3));

    // A retail (SG10) call that is both rate-able (in-partner + called number) and summary-ready.
    private static cdr Call(String called, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1; c.InPartnerId = 5; c.IncomingRoute = "in"; c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = called; c.OriginatingCalledNumber = called; c.StartTime = when; c.AnswerTime = when; c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60); c.RoundedDuration = BigDecimal.valueOf(60); c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880"; c.AnsIdTerm = 42; c.MatchedPrefixSupplier = "1712";
        return c;
    }

    // LINQ .Count(pred) / .First(pred) helpers.
    private static int count(List<String> list, Predicate<String> p) {
        return (int) list.stream().filter(p).count();
    }

    private static String first(List<String> list, Predicate<String> p) {
        return list.stream().filter(p).findFirst().orElseThrow();
    }

    @Test
    void Processes_a_batch_rates_each_and_writes_the_summary_outbox_row() {
        var store = new InMemorySqlExecutor();
        var when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        var batch = new CdrBatch(Mediation(), RetailPartner5, List.of(
                Call("8801712345678", when),
                Call("8801712000000", when),   // same prefix 1712, same bucket
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

        // summaries are OUTBOX-only: EXACTLY one summary_affected row, and NO inline sum_voice_* write.
        assertEquals(1, count(store.ExecutedSql, s -> s.startsWith("insert into summary_affected")));
        assertTrue(store.ExecutedSql.stream().noneMatch(s -> s.contains("sum_voice_")));
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
        var store = new InMemorySqlExecutor();
        // SG10's ANSWERED checklist requires a calling number; the unanswered checklist stays empty.
        ServiceGroupConfiguration base = ServiceGroupConfiguration.Defaults.get(10);
        Map<Integer, ServiceGroupConfiguration> configs = Map.of(
                10, new ServiceGroupConfiguration(base.ServiceGroupId(), base.Disabled(), base.Rules(),
                        List.<IValidationRule<cdr>>of(new RequireCallingNumber()), base.UnansweredChecklist()));
        var medFixture = TestData.fixture();
        medFixture.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        var med = medFixture.mediation(null, null, configs, null);

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
        var store = new InMemorySqlExecutor();
        var result = CdrPipeline.Default().Process(
                new CdrBatch(Mediation(), RetailPartner5, List.<cdr>of(), store));

        assertEquals(0, result.Total());
        assertTrue(result.Rated().isEmpty());
        assertTrue(store.ExecutedSql.isEmpty());
    }
}
