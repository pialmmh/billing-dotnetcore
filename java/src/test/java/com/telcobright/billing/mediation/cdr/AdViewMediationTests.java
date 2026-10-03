package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.servicegroups.IServiceGroupDetector;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupDetection;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupMatch;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.validation.InPartnerIdGt0;
import com.telcobright.billing.mediation.validation.OutPartnerIdGt0;
import com.telcobright.billing.testsupport.AdViewSamples;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service group 30, the ad view, through the mediation pipeline (routesphere ad-is-a-call §5; the brief's B2–B4):
 * <ul>
 *   <li><b>B2</b> — a record that states 30 IS 30: it never reaches a detector, whatever its payer's partner type;</li>
 *   <li><b>B3</b> — pre-rated: no rate is looked up, one customer chargeable is built from the record's settled
 *       amounts, the cdr's amounts stay as they came; a failed view is a cdr row and a chargeable of zero;</li>
 *   <li><b>B4</b> — its own answered / unanswered checklists, built in, overridden by a served configuration.</li>
 * </ul>
 * The records are the wire's (the brief's sample), mapped by the real preprocessor.
 */
class AdViewMediationTests {

    private static final class InMemorySql implements ISqlExecutor {
        final List<String> Executed = new ArrayList<>();

        @Override public int ExecuteNonQuery(String sql) { Executed.add(sql); return 1; }

        long count(String startsWith) { return Executed.stream().filter(s -> s.startsWith(startsWith)).count(); }
    }

    private static final Map<Integer, Partner> NoPartners = Map.of();

    /** A tenant that serves no rate plan, no rate and no service-group configuration: the built-in ones apply. */
    private static MediationContext BuiltIn() {
        return TestData.fixture().mediation();
    }

    private static CdrBatchResult Mediate(MediationContext mediation, Map<Integer, Partner> partners, InMemorySql sql, List<cdr> cdrs) {
        return CdrPipeline.Default().Process(new CdrBatch(mediation, partners, cdrs, sql));
    }

    private static CdrBatchResult Mediate(List<cdr> cdrs) {
        return Mediate(BuiltIn(), NoPartners, new InMemorySql(), cdrs);
    }

    private static cdr TheRefusedView() {
        return AdViewSamples.cdrsOf("btcl", AdViewSamples.A_REFUSED_VIEW).get(0);
    }

    private static cdr TheLeafTiersView() {
        return AdViewSamples.cdrsOf("res_44", AdViewSamples.ONE_VIEW_TWO_TIERS).get(0);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ── B2: a record that states 30 IS 30 ────────────────────────────────────────────────────────────────────

    @Test
    void a_stated_30_stays_30_whatever_its_payers_partner_type() {
        // 3 / 4 / 5 / 6 make a CALL group 10, 2 makes it group 11 (SgDomOffnetOut / SgDomOffnetIn); 1 and "no such
        // partner" make a call undetectable. An advertiser's type must do none of that to its ad.
        for (Integer type : new Integer[] {1, 2, 3, 4, 5, 6, null}) {
            cdr view = TheLeafTiersView();
            Map<Integer, Partner> partners = new HashMap<>();
            if (type != null) partners.put(view.InPartnerId, new Partner(view.InPartnerId, "Unilever", type));

            CdrBatchResult result = Mediate(BuiltIn(), partners, new InMemorySql(), List.of(view));

            assertEquals(1, result.Rated().size(), "partner type " + type + ": " + result.Errored().stream().map(c -> c.ErrorCode).toList());
            assertEquals(30, result.Rated().get(0).Cdr().ServiceGroup, "partner type " + type);
            assertEquals(30, result.Rated().get(0).Customer().servicegroup, "partner type " + type);
        }
    }

    @Test
    void a_stated_30_stays_30_even_with_an_international_looking_number() {
        cdr view = TheLeafTiersView();
        view.OriginatingCalledNumber = "0097180044444";       // "00…": SG15's claim on a call
        view.TerminatingCalledNumber = "0097180044444";

        CdrBatchResult result = Mediate(List.of(view));

        assertEquals(30, result.Rated().get(0).Cdr().ServiceGroup);
    }

    @Test
    void an_ad_view_is_never_handed_to_a_detector() {
        IServiceGroupDetector tripwire = new IServiceGroupDetector() {
            @Override public int Id() { return 10; }
            @Override public String RuleName() { return "tripwire"; }
            @Override public ServiceGroupMatch Detect(cdr c, Map<Integer, Partner> partners) {
                throw new AssertionError("a detector saw the ad view " + c.UniqueBillId);
            }
        };
        var pipeline = new CdrPipeline(new BasicCharge(new ServiceGroupDetection(List.of(tripwire))));

        CdrBatchResult result = pipeline.Process(new CdrBatch(BuiltIn(), NoPartners, List.of(TheLeafTiersView()), new InMemorySql()));

        assertEquals(1, result.Rated().size(), "the tripwire's error would have sent it to cdrerror: "
                + result.Errored().stream().map(c -> c.ErrorCode).toList());
    }

    // ── B3: pre-rated ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_samples_two_tiers_give_two_cdr_rows_and_two_chargeables_with_the_samples_amounts() {
        record Tier(String tenant, int payer, String amount, long account) {}
        for (Tier tier : List.of(new Tier("res_44", 1, "0.50", 3061L), new Tier("btcl", 44, "0.40", 3044L))) {
            var sql = new InMemorySql();
            List<cdr> views = AdViewSamples.cdrsOf(tier.tenant(), AdViewSamples.ONE_VIEW_TWO_TIERS);

            CdrBatchResult result = Mediate(BuiltIn(), NoPartners, sql, views);

            assertEquals(1, result.Rated().size(), tier.tenant() + ": " + result.Errored().stream().map(c -> c.ErrorCode).toList());
            assertEquals(1, sql.count("insert into cdr ("), tier.tenant() + ": one cdr row");
            assertEquals(0, sql.count("insert into cdrerror ("));
            assertEquals(1, sql.count("insert into acc_chargeable"), tier.tenant() + ": one chargeable");
            assertEquals(1, sql.count("insert into summary_affected"), tier.tenant() + ": one outbox row");

            RatedCdr rated = result.Rated().get(0);
            assertEquals(1, rated.Chargeables().size(), "ONE customer chargeable");
            acc_chargeable charge = rated.Chargeables().get(0);
            assertEquals(30, charge.servicegroup);
            assertEquals(30, charge.servicefamily);
            assertEquals((byte) 1, charge.assignedDirection);                       // customer
            assertMoney(tier.amount(), charge.BilledAmount);                        // the settled money
            assertEquals("BDT", charge.idBilledUom);                                // the unit, as sent
            assertMoney(tier.amount(), charge.unitPriceOrCharge);                   // the rate
            assertEquals("70", charge.Prefix);                                      // the prefix
            assertMoney("10", charge.Quantity);                                     // the seconds watched
            assertEquals("TF_s", charge.idQuantityUom);
            assertEquals(AdViewSamples.VIEW_ID, charge.uniqueBillId);
            assertEquals(rated.Cdr().IdCall, charge.idEvent);
            assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 3), charge.transactionTime);
            assertEquals(0L, charge.glAccountId, "a package account is not a GL account: it stays on the cdr row");
            assertEquals(0L, charge.RateId);
            assertTrue(charge.id > 0);

            cdr row = rated.Cdr();
            assertEquals(tier.payer(), row.InPartnerId);
            assertEquals(tier.account(), row.IdPackageAccount);
            assertMoney(tier.amount(), row.InPartnerCost);                          // as it came
            assertMoney(tier.amount(), row.CustomerRate);
            assertMoney("0", row.PackageAmount);
            assertEquals("BDT", row.InPartnerUom);
            assertEquals("70", row.MatchedPrefixCustomer);
            assertEquals(1, row.ChargingStatus, "answered = shown");
            assertMoney("10", row.Duration1);
            assertMoney("10", row.RoundedDuration);
            assertNull(row.ErrorCode);
        }
    }

    @Test
    void no_rate_is_needed_and_nothing_the_wire_sent_is_recomputed() {
        // The tenant has NO rate plan, NO rate and NO partner at all — a call would be RATE_NOT_FOUND. The view is
        // charged what the switch settled, and the cdr's amounts are the wire's to the digit.
        cdr view = TheLeafTiersView();
        view.CustomerRate = new BigDecimal("0.77");
        view.InPartnerCost = new BigDecimal("0.123456789012");
        view.MatchedPrefixCustomer = "7001";

        CdrBatchResult result = Mediate(List.of(view));

        cdr row = result.Rated().get(0).Cdr();
        assertEquals(new BigDecimal("0.123456789012"), row.InPartnerCost, "not rounded, not recomputed");
        assertEquals(new BigDecimal("0.77"), row.CustomerRate);
        assertEquals("7001", row.MatchedPrefixCustomer);
        assertNull(row.CountryCode, "no rate row was matched, so nothing of one is stamped");
        assertNull(row.AnsIdOrig, "no operator is looked up for an ad's subscriber");
        assertEquals(new BigDecimal("0.123456789012"), result.Rated().get(0).Customer().BilledAmount);
    }

    @Test
    void a_tier_that_paid_in_units_is_charged_in_its_unit_not_in_money() {
        cdr view = TheLeafTiersView();
        view.InPartnerCost = BigDecimal.ZERO;
        view.PackageAmount = new BigDecimal("1");
        view.InPartnerUom = "OTH_ea";

        acc_chargeable charge = Mediate(List.of(view)).Rated().get(0).Customer();

        assertMoney("1", charge.BilledAmount);
        assertEquals("OTH_ea", charge.idBilledUom, "the summary tells units from money by this field");
    }

    @Test
    void a_refused_view_gives_a_cdr_row_and_a_chargeable_of_zero_in_money() {
        var sql = new InMemorySql();

        CdrBatchResult result = Mediate(BuiltIn(), NoPartners, sql, List.of(TheRefusedView()));

        assertEquals(1, result.Rated().size(), "a failed view passes: " + result.Errored().stream().map(c -> c.ErrorCode).toList());
        assertEquals(1, sql.count("insert into cdr ("));
        assertEquals(0, sql.count("insert into cdrerror ("));
        cdr row = result.Rated().get(0).Cdr();
        assertEquals(30, row.ServiceGroup);
        assertEquals(0, row.ChargingStatus, "never shown");
        assertEquals("NO_RULE", row.HangupCause);
        acc_chargeable charge = result.Rated().get(0).Customer();
        assertEquals(1, result.Rated().get(0).Chargeables().size());
        assertMoney("0", charge.BilledAmount);
        assertEquals("BDT", charge.idBilledUom, "the wire sends no unit for a refused view: it is money 0, never a units row");
        assertMoney("0", charge.unitPriceOrCharge);
        assertMoney("0", charge.Quantity);
        assertNull(charge.Prefix);
        assertEquals(row.StartTime, charge.transactionTime);
    }

    @Test
    void a_view_admitted_and_never_shown_keeps_its_charge() {
        // The owner's rule (2026-10-04, no return policy): an admitted view is charged, shown or not.
        cdr view = TheLeafTiersView();
        view.AnswerTime = null;
        view.ConnectTime = null;
        view.ChargingStatus = 0;
        view.DurationSec = BigDecimal.ZERO;

        CdrBatchResult result = Mediate(List.of(view));

        assertEquals(1, result.Rated().size());
        assertMoney("0.50", result.Rated().get(0).Customer().BilledAmount);
    }

    // ── B4: its checklists ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_record_with_no_partner_goes_to_cdrerror() {
        for (Integer payer : new Integer[] {null, 0}) {
            var sql = new InMemorySql();
            cdr refused = TheRefusedView();
            refused.InPartnerId = payer;
            cdr shown = TheLeafTiersView();
            shown.InPartnerId = payer;

            CdrBatchResult result = Mediate(BuiltIn(), NoPartners, sql, List.of(refused, shown));

            assertEquals(0, result.Rated().size(), "in-partner " + payer);
            assertEquals(2, result.Errored().size());
            for (cdr c : result.Errored()) assertEquals("InPartnerId must be > 0", c.ErrorCode);
            assertEquals(1, sql.count("insert into cdrerror ("));
            assertEquals(0, sql.count("insert into cdr ("));
            assertEquals(0, sql.count("insert into acc_chargeable"), "nothing is charged for a record in cdrerror");
            assertEquals(0, sql.count("insert into summary_affected"));
        }
    }

    @Test
    void a_view_that_ends_before_it_starts_goes_to_cdrerror_shown_or_not() {
        cdr refused = TheRefusedView();
        refused.EndTime = refused.StartTime.minusSeconds(1);
        cdr shown = TheLeafTiersView();
        shown.EndTime = shown.StartTime.minusSeconds(1);

        CdrBatchResult result = Mediate(List.of(refused, shown));

        assertEquals(2, result.Errored().size());
        for (cdr c : result.Errored()) assertEquals("EndTime must be >= StartTime", c.ErrorCode);
    }

    @Test
    void a_shown_view_with_a_negative_duration_goes_to_cdrerror_and_a_view_never_shown_is_not_asked() {
        cdr shown = TheLeafTiersView();
        shown.DurationSec = new BigDecimal("-1");
        cdr neverShown = TheRefusedView();
        neverShown.DurationSec = new BigDecimal("-1");

        CdrBatchResult result = Mediate(List.of(shown, neverShown));

        assertEquals(List.of("DurationSec must be >= 0"), result.Errored().stream().map(c -> c.ErrorCode).toList());
        assertEquals(1, result.Rated().size(), "the duration rule is the ANSWERED checklist's only");
    }

    @Test
    void a_served_configuration_of_group_30_overrides_the_built_in_one() {
        // The tenant serves its own SG30: it also wants an out-partner on a view that was never shown.
        Map<Integer, ServiceGroupConfiguration> served = Map.of(30, new ServiceGroupConfiguration(30, false,
                List.<Rule>of(new RatingRule(30, 1, null)),
                List.of(new InPartnerIdGt0()),
                List.of(new InPartnerIdGt0(), new OutPartnerIdGt0())));
        MediationContext mediation = TestData.fixture().mediation(null, null, served, null);

        CdrBatchResult result = Mediate(mediation, NoPartners, new InMemorySql(), List.of(TheRefusedView()));

        assertEquals(List.of("OutPartnerId must be > 0"), result.Errored().stream().map(c -> c.ErrorCode).toList());
    }

    @Test
    void a_tenant_that_serves_configurations_without_group_30_still_has_the_built_in_one() {
        Map<Integer, ServiceGroupConfiguration> servedCallsOnly = Map.of(10, ServiceGroupConfiguration.Defaults.get(10));
        MediationContext mediation = TestData.fixture().mediation(null, null, servedCallsOnly, null);
        cdr noPartner = TheRefusedView();
        noPartner.InPartnerId = null;

        CdrBatchResult result = Mediate(mediation, NoPartners, new InMemorySql(), List.of(TheLeafTiersView(), noPartner));

        assertEquals(1, result.Rated().size(), "the built-in rule charges it");
        assertEquals(List.of("InPartnerId must be > 0"), result.Errored().stream().map(c -> c.ErrorCode).toList());
        assertTrue(mediation.ServiceGroupConfigurations.containsKey(10));
        assertTrue(!mediation.ServiceGroupConfigurations.containsKey(11), "a served map still replaces the call groups wholesale");
    }

    @Test
    void a_tenant_that_disables_group_30_charges_nothing_for_it() {
        Map<Integer, ServiceGroupConfiguration> served = Map.of(30, new ServiceGroupConfiguration(30, true,
                List.<Rule>of(new RatingRule(30, 1, null)), List.of(), List.of()));
        MediationContext mediation = TestData.fixture().mediation(null, null, served, null);

        CdrBatchResult result = Mediate(mediation, NoPartners, new InMemorySql(), List.of(TheLeafTiersView(), TheRefusedView()));

        // as a disabled SG10: a watched view (duration > 0) has no customer leg -> cdrerror; a failed one is a cdr row.
        assertEquals(1, result.Errored().size());
        assertTrue(result.Errored().get(0).ErrorCode.startsWith("RATE_NOT_FOUND"), result.Errored().get(0).ErrorCode);
        assertEquals(1, result.Rated().size());
        assertTrue(result.Rated().get(0).Chargeables().isEmpty());
    }

    @Test
    void a_rule_of_group_30_that_names_a_family_which_needs_a_rate_is_passed_over() {
        // A served SG30 that names SF10 (A2Z + VAT): that family cannot charge without a matched rate, and no rate
        // is ever matched for an ad view. It is passed over — it must not blow up on the missing rate.
        Map<Integer, ServiceGroupConfiguration> served = Map.of(30, new ServiceGroupConfiguration(30, false,
                List.<Rule>of(new RatingRule(10, 1, null)), List.of(), List.of()));
        MediationContext mediation = TestData.fixture().mediation(null, null, served, null);

        CdrBatchResult result = Mediate(mediation, NoPartners, new InMemorySql(), List.of(TheRefusedView()));

        assertEquals(1, result.Rated().size(), "not 'mediation failed': " + result.Errored().stream().map(c -> c.ErrorCode).toList());
        assertTrue(result.Rated().get(0).Chargeables().isEmpty());
    }

    @Test
    void the_built_in_configurations_of_the_call_groups_are_what_they_were() {
        ServiceGroupConfiguration sg10 = ServiceGroupConfiguration.Defaults.get(10);
        ServiceGroupConfiguration sg11 = ServiceGroupConfiguration.Defaults.get(11);

        assertEquals(List.of(new RatingRule(10, 1, null), new RatingRule(1, 2, null)), sg10.Rules());
        assertEquals(List.of(new RatingRule(11, 1, null)), sg11.Rules());
        assertTrue(sg10.AnsweredChecklist().isEmpty() && sg10.UnansweredChecklist().isEmpty());
        assertTrue(sg11.AnsweredChecklist().isEmpty() && sg11.UnansweredChecklist().isEmpty());
        assertEquals(3, ServiceGroupConfiguration.Defaults.size());
    }
}
