package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.A2ZRateResult;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.rating.FinalizeEngine;
import com.telcobright.billing.mediation.rating.FinalizeFacts;
import com.telcobright.billing.mediation.rating.FinalizeTierInput;
import com.telcobright.billing.mediation.rating.ServiceType;
import com.telcobright.billing.mediation.rating.TierMode;
import com.telcobright.billing.mediation.rating.TierReserved;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cdr.Duration1} on a RESELLER tier must be the duration the amount was actually RATED over, per that
 * tier's own assigned rate plan — the plan's three surcharge branches summed, not the amount path's working
 * duration, which legacy leaves at 0 for every call that runs past the initial period even though the call is
 * charged.
 *
 * <p><b>Scope.</b> Reseller tiers only ({@code MediationContext.IsResellerTier}, set by {@code TenantTreeBuilder}
 * for any tenant with a parent). The root/admin tenant keeps the legacy value verbatim — pinned by
 * {@link #Admin_tenant_keeps_the_legacy_value_for_the_same_configuration}.
 *
 * <p><b>Reporting only.</b> {@code acc_chargeable.Quantity}, {@code BilledAmount}, the tax, the surcharge
 * behaviour and the package-minute deduction all still come from {@code A2ZRater.Rate} untouched — pinned across
 * the whole configuration matrix, and end-to-end through {@link FinalizeEngine} for the package path.
 *
 * <p>Expected values come from {@link #ExpectedRatedDuration}, an INDEPENDENT oracle written from the business
 * rule, never from the production helper under test. It is deliberately restated here rather than shared with
 * {@link ResellerRoundedDurationTests}: two features, two oracles, so one edit cannot silently bless both.
 */
class ResellerBilledDurationTests {

    /** An awkward, deliberately non-round duration so every rule produces a distinguishable result. */
    private static final BigDecimal Duration = new BigDecimal("93.643");

    // ---------------------------------------------------------------- the oracle

    /**
     * The plan's rounding of a span: {@code MinDurationSec} is a THRESHOLD on the fractional second (&lt;0 = keep
     * the actual value, 0 = always ceil, &gt;0 = ceil when the fraction reaches it, else floor), then up to the
     * next whole multiple of {@code Resolution} (the pulse).
     */
    private static BigDecimal ExpectedRounding(Rateext rate, BigDecimal actual) {
        if (actual.signum() == 0) return BigDecimal.ZERO;

        BigDecimal threshold = new BigDecimal(Float.toString(rate.MinDurationSec));
        if (threshold.signum() < 0) return actual;

        BigDecimal whole;
        if (threshold.signum() == 0) {
            whole = actual.setScale(0, RoundingMode.CEILING);
        } else {
            BigDecimal fraction = actual.subtract(actual.setScale(0, RoundingMode.FLOOR));
            whole = fraction.compareTo(threshold) >= 0
                    ? actual.setScale(0, RoundingMode.CEILING)
                    : actual.setScale(0, RoundingMode.FLOOR);
        }

        if (rate.Resolution <= 0) return whole;
        long seconds = whole.longValueExact();
        long pulse = rate.Resolution;
        long pulses = (seconds % pulse == 0) ? seconds / pulse : (seconds / pulse) + 1;
        return BigDecimal.valueOf(pulses * pulse);
    }

    /**
     * The duration the plan actually charges for, restated from the pricing rule: with no initial period the
     * whole call is rounded; within the initial period the initial period is charged whole; past it the initial
     * period is charged whole PLUS the rounded remainder — which is exactly the two spans the amount is priced
     * over.
     */
    private static BigDecimal ExpectedRatedDuration(Rateext rate, BigDecimal actual) {
        if (rate.SurchargeTime == 0) return ExpectedRounding(rate, actual);
        BigDecimal initialPeriod = BigDecimal.valueOf(rate.SurchargeTime);
        if (actual.compareTo(initialPeriod) <= 0) return initialPeriod;
        return initialPeriod.add(ExpectedRounding(rate, actual.subtract(initialPeriod)));
    }

    // ---------------------------------------------------------------- tenant assembly

    /** One rate-plan shape: the pulse, the round-up threshold and the initial period. */
    private record PlanShape(String label, int resolution, float minDurationSec, int surchargeTime) {}

    /** The live reseller plan: per-second pulse, floor-only, 60 s initial period. */
    private static final PlanShape LivePlan =
            new PlanShape("per-second, floor-only, 60s initial period (the live reseller plan)", 1, 1f, 60);

    /**
     * A plan whose initial period is NOT a whole multiple of its pulse. Worth calling out: whenever the period
     * IS such a multiple (every shape the live estate uses), splitting the call at the period and rounding the
     * remainder gives the same answer as rounding the whole call — so {@code Duration1} and
     * {@code RoundedDuration} coincide there by arithmetic, not by being derived from one another. This shape is
     * where they genuinely diverge, which is what makes their independence testable at all.
     */
    private static final PlanShape UnalignedPeriod =
            new PlanShape("15s pulse, floor-only, 20s initial period (period is not a pulse multiple)", 15, 1f, 20);

    /** The configuration matrix — spans every branch of the rule, not one blessed setup. */
    private static final List<PlanShape> Shapes = List.of(
            LivePlan,
            new PlanShape("per-second, 100ms round-up threshold, 60s initial period", 1, 0.1f, 60),
            new PlanShape("per-second, always ceil, 60s initial period", 1, 0f, 60),
            new PlanShape("15s pulse, 100ms threshold, 30s initial period", 15, 0.1f, 30),
            new PlanShape("30s pulse, floor-only, 120s initial period", 30, 1f, 120),
            new PlanShape("60s pulse, always ceil, no initial period", 60, 0f, 0),
            new PlanShape("no rounding at all, no initial period", 60, -1f, 0),
            new PlanShape("no pulse configured, 6s initial period", 0, 0.1f, 6),
            new PlanShape("per-second, floor-only, no initial period", 1, 1f, 0),
            UnalignedPeriod);

    /**
     * A RESELLER tenant: partner {@code idPartner} assigned rate plan {@code idRatePlan} for SG10 customer
     * traffic, that plan holding a single 880 rate carrying the shape's knobs. {@code IsResellerTier} is what
     * {@code TenantTreeBuilder} sets for any tenant that has a parent.
     */
    private static MediationContext ResellerTenant(int idPartner, int idRatePlan, PlanShape shape) {
        MediationContext med = Tenant(idPartner, idRatePlan, shape);
        med.IsResellerTier = true;
        return med;
    }

    /** The same tenant WITHOUT the reseller flag — i.e. the root/admin tenant. */
    private static MediationContext Tenant(int idPartner, int idRatePlan, PlanShape shape) {
        var f = TestData.fixture();
        var ra = TestData.Ra(880, "0.50")
                .resolution(shape.resolution()).minDurationSec(shape.minDurationSec())
                .billingspan(60).idRatePlan(idRatePlan);
        if (shape.surchargeTime() > 0) ra.surchargeTime(shape.surchargeTime()).surchargeAmount("60");
        f.tup(10, AssignmentDirection.Customer.value, idPartner, null, 0, ra);
        return f.mediation();
    }

    private static final String CalledNumber = "8801700000000";

    private static cdr CallOf(BigDecimal durationSec, int idPartner) {
        cdr c = new cdr();
        c.DurationSec = durationSec;
        c.InPartnerId = idPartner;
        c.OriginatingCalledNumber = CalledNumber;
        c.TerminatingCalledNumber = CalledNumber;
        return c;
    }

    private static Map<Integer, Partner> CustomerPartner(int idPartner) {
        Map<Integer, Partner> m = new HashMap<>();
        m.put(idPartner, new Partner(idPartner, null, 3));
        return m;
    }

    /** Rate the call on this tenant and hand back the cdr + the chargeable, so both sides can be asserted. */
    private record Rated(cdr Cdr, acc_chargeable Chargeable, Rateext Rate) {}

    private static Rated Rate(MediationContext med, int idPartner, BigDecimal duration) {
        var engine = BasicCharge.Default();
        Rateext rate = engine.MatchCustomerRate(CallOf(duration, idPartner), med, CustomerPartner(idPartner)).Rate();
        assertNotNull(rate, "the tenant's own assignment must resolve a rate");

        cdr call = CallOf(duration, idPartner);
        List<acc_chargeable> chargeables = engine.Rate(call, med, CustomerPartner(idPartner));
        assertEquals(1, chargeables.size(), "customer leg only, no cost leg");
        return new Rated(call, chargeables.get(0), rate);
    }

    // ---------------------------------------------------------------- 1. the three branches

    /** No initial period: the whole call is priced off the plan's rounding, so that is the billed duration. */
    @Test
    void With_no_initial_period_the_whole_call_is_billed() {
        var shape = new PlanShape("per-second, 100ms threshold, no initial period", 1, 0.1f, 0);
        var rated = Rate(ResellerTenant(1201, 1301, shape), 1201, Duration);

        assertEquals(0, ExpectedRatedDuration(rated.Rate(), Duration).compareTo(rated.Cdr().Duration1));
    }

    /** Inside the initial period the whole period is charged, so the billed duration is that period. */
    @Test
    void Below_the_initial_period_the_initial_period_is_billed() {
        BigDecimal shortCall = new BigDecimal("12.32");
        var rated = Rate(ResellerTenant(1202, 1302, LivePlan), 1202, shortCall);

        assertEquals(0, ExpectedRatedDuration(rated.Rate(), shortCall).compareTo(rated.Cdr().Duration1));
        assertEquals(0, BigDecimal.valueOf(rated.Rate().SurchargeTime).compareTo(rated.Cdr().Duration1),
                "a call inside the initial period bills exactly that period");
    }

    /** The boundary: exactly the initial period is still the "within" branch, not the "past it" branch. */
    @Test
    void Exactly_the_initial_period_is_billed_as_the_initial_period() {
        BigDecimal exact = BigDecimal.valueOf(LivePlan.surchargeTime());
        var rated = Rate(ResellerTenant(1203, 1303, LivePlan), 1203, exact);

        assertEquals(0, ExpectedRatedDuration(rated.Rate(), exact).compareTo(rated.Cdr().Duration1));
        assertEquals(0, exact.compareTo(rated.Cdr().Duration1),
                "the boundary belongs to the initial-period branch");
    }

    /** Past the initial period: the period PLUS the plan's rounding of the remainder — the regression. */
    @Test
    void Above_the_initial_period_bills_the_period_plus_the_rounded_remainder() {
        var rated = Rate(ResellerTenant(1204, 1304, LivePlan), 1204, Duration);

        assertEquals(0, ExpectedRatedDuration(rated.Rate(), Duration).compareTo(rated.Cdr().Duration1));
        assertTrue(rated.Cdr().Duration1.signum() > 0,
                "the regression: a charged call past the initial period must not report 0 seconds");
    }

    /**
     * The call this was raised for, on the live plan shape ({@code Resolution 1}, {@code MinDurationSec 1},
     * {@code SurchargeTime 60}): 93.643 s bills 60 + floor(33.643) = 93 s, where legacy reported 0.
     *
     * <p>The 93 is asserted against the INDEPENDENT oracle first, so the number is pinned by the restated
     * business rule rather than by the production code agreeing with itself.
     */
    @Test
    void The_documented_reseller_call_bills_the_period_plus_the_remainder() {
        var med = ResellerTenant(1205, 1305, LivePlan);
        var rated = Rate(med, 1205, Duration);
        Rateext rate = rated.Rate();

        assertEquals(0, new BigDecimal("93").compareTo(ExpectedRatedDuration(rate, Duration)),
                "the documented example: 60s initial period + floor(33.643) = 93");
        assertEquals(0, ExpectedRatedDuration(rate, Duration).compareTo(rated.Cdr().Duration1));

        // ...and the amount path still reports its legacy 0 for the same call — the two are now distinct.
        A2ZRateResult legacy = A2ZRater.Rate(rate, Duration, med.DicRatePlan, med.BillingSpans,
                med.MaxDecimalPrecision);
        assertEquals(0, BigDecimal.ZERO.compareTo(legacy.BilledDurationSec()));
        assertEquals(0, legacy.BilledDurationSec().compareTo(rated.Chargeable().Quantity),
                "Quantity keeps the legacy value");
    }

    // ---------------------------------------------------------------- 2. every plan shape

    /** Whatever the assigned plan configures — pulse, threshold, initial period — that is what comes out. */
    @Test
    void Every_rate_plan_configuration_drives_its_own_billed_duration() {
        int partner = 1400, plan = 1500;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            var rated = Rate(ResellerTenant(partner, plan, shape), partner, Duration);

            assertEquals(0, ExpectedRatedDuration(rated.Rate(), Duration).compareTo(rated.Cdr().Duration1),
                    "plan shape: " + shape.label() + " -> got " + rated.Cdr().Duration1);
        }
    }

    /**
     * The property that names the defect, independent of the oracle: wherever the plan charges money, the
     * billed duration must be non-zero.
     */
    @Test
    void A_charged_call_never_reports_a_zero_billed_duration() {
        int partner = 1600, plan = 1700;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            var rated = Rate(ResellerTenant(partner, plan, shape), partner, Duration);

            assertTrue(rated.Chargeable().BilledAmount.signum() > 0, shape.label() + ": expected a charge");
            assertTrue(rated.Cdr().Duration1.signum() > 0,
                    shape.label() + ": charged " + rated.Chargeable().BilledAmount + " but billed 0 seconds");
        }
    }

    /**
     * Two plans differing only in their initial period must bill different durations for the same call — shown
     * on {@link #UnalignedPeriod}, where splitting the call genuinely changes the answer.
     */
    @Test
    void The_initial_period_is_part_of_the_rule() {
        var withPeriod = UnalignedPeriod;
        var without = new PlanShape("15s pulse, floor-only, no initial period", 15, 1f, 0);

        var a = Rate(ResellerTenant(1801, 1901, withPeriod), 1801, Duration);
        var b = Rate(ResellerTenant(1802, 1902, without), 1802, Duration);

        assertEquals(0, ExpectedRatedDuration(a.Rate(), Duration).compareTo(a.Cdr().Duration1));
        assertEquals(0, ExpectedRatedDuration(b.Rate(), Duration).compareTo(b.Cdr().Duration1));
        assertNotEquals(0, a.Cdr().Duration1.compareTo(b.Cdr().Duration1),
                "the initial period must change the billed duration");
    }

    // ---------------------------------------------------------------- 3. tiers

    /**
     * Tier 1 → Tier N: each tier is its own tenant with its own partner, assignment and plan, so each leg of the
     * same call bills by ITS OWN plan. Nothing is shared but the duration.
     */
    @Test
    void Every_tier_in_the_chain_bills_by_its_own_assigned_plan() {
        record Tier(int partner, int plan, PlanShape shape) {}
        var chain = List.of(
                new Tier(261, 2001, Shapes.get(4)),    // tier 1 — 30s pulse, 120s initial period
                new Tier(262, 2002, Shapes.get(3)),    // tier 2 — 15s pulse, 30s initial period
                new Tier(263, 2003, LivePlan),         // tier 3 — per-second, 60s initial period
                new Tier(264, 2004, UnalignedPeriod)); // tier 4 — 15s pulse, 20s initial period

        var results = new ArrayList<BigDecimal>();
        for (Tier tier : chain) {
            var rated = Rate(ResellerTenant(tier.partner(), tier.plan(), tier.shape()), tier.partner(), Duration);

            assertEquals(tier.plan(), rated.Rate().idrateplan.intValue(), "tier must resolve its OWN plan");
            assertEquals(tier.partner(), rated.Rate().IdPartner, "...through its own partner-keyed tuple");
            assertTrue(rated.Rate().IdRatePlanAssignmentTuple > 0, "...carrying its assignment tuple");
            assertEquals(0, ExpectedRatedDuration(rated.Rate(), Duration).compareTo(rated.Cdr().Duration1),
                    "tier partner " + tier.partner() + " must bill by plan " + tier.plan());
            results.add(rated.Cdr().Duration1);
        }
        assertEquals(results.size(), results.stream().map(BigDecimal::stripTrailingZeros).distinct().count(),
                "these four tier configurations must each produce a distinct billed duration");
    }

    // ---------------------------------------------------------------- 4. nothing financial moved

    /**
     * Across the whole matrix, every financial output must still equal what {@code A2ZRater.Rate} produces on
     * its own for the SAME matched rate: amount, tax, partner cost and — the one this change could plausibly
     * have disturbed — {@code Quantity}, which still carries the amount path's working duration.
     */
    @Test
    void Quantity_amount_and_tax_are_untouched_across_every_plan_shape() {
        int partner = 2100, plan = 2200;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            MediationContext med = ResellerTenant(partner, plan, shape);
            var rated = Rate(med, partner, Duration);

            A2ZRateResult expected = A2ZRater.Rate(rated.Rate(), Duration, med.DicRatePlan, med.BillingSpans,
                    med.MaxDecimalPrecision);

            assertEquals(0, expected.Amount().compareTo(rated.Chargeable().BilledAmount),
                    shape.label() + ": BilledAmount moved");
            assertEquals(0, expected.Amount().compareTo(rated.Cdr().InPartnerCost),
                    shape.label() + ": InPartnerCost moved");
            assertEquals(0, expected.BilledDurationSec().compareTo(rated.Chargeable().Quantity),
                    shape.label() + ": Quantity moved");
            assertEquals(0, BigDecimal.ZERO.compareTo(rated.Cdr().Tax1), shape.label() + ": Tax1 moved");
        }
    }

    /**
     * The package path end to end: {@code FinalizeEngine} deducts {@code Quantity / 60} minutes, so the
     * settlement must be byte-identical to what the amount path alone yields — even on the live plan, where the
     * cdr now reports 93 s and the deduction is still the legacy 0.
     */
    @Test
    void Package_minute_deduction_is_untouched() {
        final int partner = 2301, plan = 2401, billsec = 93;
        MediationContext med = ResellerTenant(partner, plan, LivePlan);

        // what the amount path alone would deduct, computed straight off A2ZRater
        Rateext rate = BasicCharge.Default()
                .MatchCustomerRate(CallOf(BigDecimal.valueOf(billsec), partner), med, CustomerPartner(partner)).Rate();
        A2ZRateResult expected = A2ZRater.Rate(rate, BigDecimal.valueOf(billsec), med.DicRatePlan,
                med.BillingSpans, med.MaxDecimalPrecision);
        BigDecimal expectedMinutes = expected.BilledDurationSec()
                .divide(BigDecimal.valueOf(60), java.math.MathContext.DECIMAL128)
                .setScale(8, RoundingMode.HALF_EVEN);

        var facts = new FinalizeFacts("res_261", "8801999000111", CalledNumber, ServiceType.Voice, 1, "in", "out",
                0, LocalDateTime.of(2026, 6, 19, 0, 0, 0), billsec, true, "uid-1");
        var tier = new FinalizeTierInput("res_261", partner, med, CustomerPartner(partner), TierMode.CustomerOnly,
                new TierReserved(200, "TF_min", new BigDecimal("5.0")));

        var settlement = FinalizeEngine.Default().Finalize(facts, List.of(tier)).Settlements().get("res_261");

        assertEquals("TF_min", settlement.Uom());
        assertEquals(0, expectedMinutes.compareTo(settlement.PackageAmount()), "package minutes moved");
        assertEquals(0, expectedMinutes.compareTo(settlement.Charged()), "the package charge moved");

        // ...while the cdr the same configuration produces DOES report the rated duration — proving the two are
        // genuinely separate rather than the deduction merely happening to be unchanged.
        var rated = Rate(med, partner, BigDecimal.valueOf(billsec));
        assertEquals(0, ExpectedRatedDuration(rate, BigDecimal.valueOf(billsec)).compareTo(rated.Cdr().Duration1));
        assertNotEquals(0, rated.Cdr().Duration1.compareTo(expected.BilledDurationSec()));
    }

    /** The supplier leg's own duration field is not part of this change. */
    @Test
    void Supplier_duration_is_untouched() {
        cdr call = CallOf(Duration, 2501);
        call.OutPartnerId = 74;
        Rateext rate = TestData.Ra(880, "0.50").resolution(1).minDurationSec(1f)
                .surchargeTime(60).surchargeAmount("60").billingspan(60).idRatePlan(1).rex();
        MediationContext reseller = new MediationContext();
        reseller.IsResellerTier = true;

        new SfA2Z().Charge(rate, call, 10, AssignmentDirection.Supplier, reseller);

        assertEquals(0, BigDecimal.ZERO.compareTo(call.Duration2),
                "Duration2 keeps the amount path's working duration on every tier");
    }

    // ---------------------------------------------------------------- 5. admin tenant unchanged

    /**
     * ADMIN / root guard: the identical rate configuration on a tenant WITHOUT the reseller flag must still
     * write the legacy value, so the operator's cdrs stay per-call comparable with the legacy biller.
     */
    @Test
    void Admin_tenant_keeps_the_legacy_value_for_the_same_configuration() {
        final int partner = 2601, plan = 2701;
        MediationContext admin = Tenant(partner, plan, LivePlan);
        assertTrue(!admin.IsResellerTier, "a tenant with no parent must not be flagged a reseller tier");

        var rated = Rate(admin, partner, Duration);
        A2ZRateResult legacy = A2ZRater.Rate(rated.Rate(), Duration, admin.DicRatePlan, admin.BillingSpans,
                admin.MaxDecimalPrecision);

        assertEquals(0, legacy.BilledDurationSec().compareTo(rated.Cdr().Duration1),
                "the admin tenant must keep the amount path's working duration");
        assertEquals(0, BigDecimal.ZERO.compareTo(rated.Cdr().Duration1),
                "...which on this plan is the legacy 0");

        // the reseller tenant on the SAME configuration diverges — that is the whole scope of the change
        var reseller = Rate(ResellerTenant(partner, plan, LivePlan), partner, Duration);
        assertNotEquals(0, reseller.Cdr().Duration1.compareTo(rated.Cdr().Duration1));
    }

    /** Every plan shape: the admin tenant's Duration1 is exactly the amount path's working duration. */
    @Test
    void Admin_tenant_is_unchanged_across_every_plan_shape() {
        int partner = 2800, plan = 2900;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            MediationContext admin = Tenant(partner, plan, shape);
            var rated = Rate(admin, partner, Duration);
            A2ZRateResult legacy = A2ZRater.Rate(rated.Rate(), Duration, admin.DicRatePlan, admin.BillingSpans,
                    admin.MaxDecimalPrecision);

            assertEquals(0, legacy.BilledDurationSec().compareTo(rated.Cdr().Duration1), shape.label());
            assertEquals(0, legacy.BilledDurationSec().compareTo(rated.Chargeable().Quantity), shape.label());
        }
    }

    // ---------------------------------------------------------------- 6. RoundedDuration stays independent

    /**
     * {@code RoundedDuration} must remain its own RatePlan-driven value, not a by-product of this one: it is the
     * plan's rounding of the WHOLE call (no surcharge term), and each follows its own rule across the matrix.
     *
     * <p>They agree on most shapes — see {@link #UnalignedPeriod} for why that is arithmetic rather than
     * coupling — so the matrix deliberately includes a shape where they diverge, and the test fails if none
     * does: without one, {@code Duration1} could be silently derived from {@code RoundedDuration} and every
     * assertion here would still pass.
     */
    @Test
    void Rounded_duration_stays_independent_of_the_billed_duration() {
        int partner = 3000, plan = 3100;
        boolean sawADifference = false;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            var rated = Rate(ResellerTenant(partner, plan, shape), partner, Duration);

            assertEquals(0, ExpectedRounding(rated.Rate(), Duration).compareTo(rated.Cdr().RoundedDuration),
                    shape.label() + ": RoundedDuration must stay the plan's rounding of the WHOLE call");
            assertEquals(0, ExpectedRatedDuration(rated.Rate(), Duration).compareTo(rated.Cdr().Duration1),
                    shape.label() + ": Duration1 must stay the rated duration");
            if (rated.Cdr().RoundedDuration.compareTo(rated.Cdr().Duration1) != 0) sawADifference = true;
        }
        assertTrue(sawADifference,
                "if the two never differed, one could be silently derived from the other and go unnoticed");
    }

    /** RoundedDuration is stamped on the admin tenant too — the scoping applies to Duration1 alone. */
    @Test
    void Rounded_duration_is_still_stamped_on_a_non_reseller_tenant() {
        var rated = Rate(Tenant(3201, 3301, LivePlan), 3201, Duration);

        assertNotNull(rated.Cdr().RoundedDuration);
        assertEquals(0, ExpectedRounding(rated.Rate(), Duration).compareTo(rated.Cdr().RoundedDuration));
    }

    // ---------------------------------------------------------------- 7. guards

    /**
     * A null {@code DurationSec} cannot reach the stamp through a family ({@code A2ZRater.Rate} dereferences it
     * first), so the direct path is guarded: it falls back to the legacy value rather than throwing.
     */
    @Test
    void Null_duration_falls_back_to_the_legacy_value() {
        cdr call = CallOf(null, 262);
        Rateext rate = TestData.Ra(880, "0.50").resolution(1).minDurationSec(1f)
                .surchargeTime(60).surchargeAmount("60").billingspan(60).idRatePlan(1).rex();
        MediationContext reseller = new MediationContext();
        reseller.IsResellerTier = true;

        FamilyStamp.StampLeg(call, rate, AssignmentDirection.Customer,
                new A2ZRateResult(new BigDecimal("7"), BigDecimal.ONE), reseller);

        assertEquals(0, new BigDecimal("7").compareTo(call.Duration1));
    }

    /** A zero-duration (unanswered) call on a plan with an initial period bills that period, as legacy does. */
    @Test
    void Zero_duration_matches_the_legacy_branch() {
        MediationContext med = ResellerTenant(3401, 3501, LivePlan);
        Rateext rate = BasicCharge.Default()
                .MatchCustomerRate(CallOf(BigDecimal.ZERO, 3401), med, CustomerPartner(3401)).Rate();
        A2ZRateResult legacy = A2ZRater.Rate(rate, BigDecimal.ZERO, med.DicRatePlan, med.BillingSpans,
                med.MaxDecimalPrecision);

        cdr call = CallOf(BigDecimal.ZERO, 3401);
        BasicCharge.Default().Rate(call, med, CustomerPartner(3401));

        assertEquals(0, legacy.BilledDurationSec().compareTo(call.Duration1),
                "a 0-duration call takes the same branch it always did");
    }
}
