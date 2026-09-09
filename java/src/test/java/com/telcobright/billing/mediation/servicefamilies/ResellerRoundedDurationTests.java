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
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cdr.RoundedDuration} must be the RATE-PLAN-WISE rounded duration: whatever {@code Resolution} /
 * {@code MinDurationSec} the rate matched from THAT tier's assigned rate plan carries, applied to the ACTUAL
 * duration — and nothing else. No fixed expectations, no dependency on the idService=20 vendor-cost leg, and
 * no effect on the amount path.
 *
 * <p><b>These tests drive the FULL resolution path</b> — {@code BasicCharge.Rate} -&gt; partner-keyed tuple
 * -&gt; {@code rateassign} -&gt; rate plan -&gt; {@code RateCache} longest-prefix -&gt; family -&gt;
 * {@code FamilyStamp} — over tenants assembled by {@link TestData.Fixture}. Handing a {@code Rateext} straight
 * to a family would prove the arithmetic but NOT that the rate came from the reseller's own assignment, which
 * is the property that actually matters here.
 *
 * <p>Expected values are computed by {@link #ExpectedRounding}, an INDEPENDENT oracle written from the
 * documented business rule, never by literals and never by calling the production
 * {@link A2ZRater#GetA2ZDuration} the code under test uses.
 */
class ResellerRoundedDurationTests {

    /** An awkward, deliberately non-round duration so every rounding rule produces a distinguishable result. */
    private static final BigDecimal Duration = new BigDecimal("93.643");

    // ---------------------------------------------------------------- the oracle

    /**
     * The rounding rule, restated independently of the implementation: {@code MinDurationSec} is a THRESHOLD on
     * the fractional second (&lt;0 = keep the actual duration, 0 = always ceil, &gt;0 = ceil when the fraction
     * reaches it, else floor), then round UP to the next whole multiple of {@code Resolution} (the pulse).
     * {@code SurchargeTime} plays no part — it belongs to the amount path.
     */
    private static BigDecimal ExpectedRounding(Rateext rate, BigDecimal actual) {
        if (actual.signum() == 0) return BigDecimal.ZERO;

        BigDecimal threshold = new BigDecimal(Float.toString(rate.MinDurationSec));
        if (threshold.signum() < 0) return actual;                       // no rounding at all

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

    // ---------------------------------------------------------------- tenant assembly

    /** One rate-plan shape: the knobs a plan can carry that this feature reads (plus the surcharge it must ignore). */
    private record PlanShape(String label, int resolution, float minDurationSec, int surchargeTime) {}

    /** The configuration matrix — deliberately spans every branch of the rule, not one blessed setup. */
    private static final List<PlanShape> Shapes = List.of(
            new PlanShape("per-second, floor-only (the live reseller plan)", 1, 1f, 60),
            new PlanShape("per-second, 100ms round-up threshold", 1, 0.1f, 60),
            new PlanShape("per-second, always ceil", 1, 0f, 60),
            new PlanShape("15s pulse, 100ms threshold", 15, 0.1f, 60),
            new PlanShape("30s pulse, floor-only", 30, 1f, 0),
            new PlanShape("60s pulse, always ceil", 60, 0f, 0),
            new PlanShape("no rounding at all", 60, -1f, 0),
            new PlanShape("no pulse configured", 0, 0.1f, 0));

    /**
     * A reseller tenant: partner {@code idPartner} assigned rate plan {@code idRatePlan} for SG10 customer
     * traffic, that plan holding a single 880 rate carrying the shape's rounding knobs.
     */
    private static MediationContext ResellerTenant(int idPartner, int idRatePlan, PlanShape shape) {
        var f = TestData.fixture();
        var ra = TestData.Ra(880, "0.50")
                .resolution(shape.resolution()).minDurationSec(shape.minDurationSec())
                .billingspan(60).idRatePlan(idRatePlan);
        if (shape.surchargeTime() > 0) ra.surchargeTime(shape.surchargeTime()).surchargeAmount("60");
        f.tup(10, AssignmentDirection.Customer.value, idPartner, null, 0, ra);
        MediationContext med = f.mediation();
        med.IsResellerTier = true;    // what TenantTreeBuilder sets for any tenant with a parent
        return med;
    }

    private static cdr CallOf(BigDecimal durationSec, int idPartner) {
        cdr c = new cdr();
        c.DurationSec = durationSec;
        c.InPartnerId = idPartner;
        c.OriginatingCalledNumber = "8801700000000";
        c.TerminatingCalledNumber = "8801700000000";
        return c;
    }

    private static Map<Integer, Partner> CustomerPartner(int idPartner) {
        Map<Integer, Partner> m = new HashMap<>();
        m.put(idPartner, new Partner(idPartner, null, 3));
        return m;
    }

    // ---------------------------------------------------------------- 1. provenance

    /**
     * Proves the rounding is driven by the rate resolved from THIS reseller's assignment: the matched
     * {@code Rateext} carries the assigned plan id, the assignment tuple and the partner, and the stamped
     * RoundedDuration is that rate's own rule applied to the actual duration.
     */
    @Test
    void Rounding_comes_from_the_rate_matched_via_the_resellers_own_assignment() {
        final int partner = 262, plan = 101;
        MediationContext med = ResellerTenant(partner, plan, Shapes.get(3));   // 15s pulse
        var engine = BasicCharge.Default();

        var matched = engine.MatchCustomerRate(CallOf(Duration, partner), med, CustomerPartner(partner));
        assertEquals(10, matched.ServiceGroupId());
        Rateext rate = matched.Rate();
        assertNotNull(rate, "the reseller's assignment must resolve a rate");
        assertEquals(plan, rate.idrateplan.intValue(), "the rate must come from the plan ASSIGNED to this reseller");
        assertEquals(partner, rate.IdPartner, "...through that reseller's own partner-keyed tuple");
        assertTrue(rate.IdRatePlanAssignmentTuple > 0, "...carrying its rate-plan assignment tuple");

        cdr call = CallOf(Duration, partner);
        engine.Rate(call, med, CustomerPartner(partner));

        assertNotNull(call.RoundedDuration);
        assertEquals(0, ExpectedRounding(rate, Duration).compareTo(call.RoundedDuration),
                "RoundedDuration must be the matched plan's own rounding of the actual duration");
    }

    // ---------------------------------------------------------------- 2. every plan shape

    /** Whatever the assigned plan configures, that is what comes out — across the whole matrix. */
    @Test
    void Every_rate_plan_configuration_drives_its_own_rounding() {
        int partner = 300, plan = 200;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            MediationContext med = ResellerTenant(partner, plan, shape);
            var engine = BasicCharge.Default();
            Rateext rate = engine.MatchCustomerRate(CallOf(Duration, partner), med, CustomerPartner(partner)).Rate();
            assertNotNull(rate, shape.label());

            cdr call = CallOf(Duration, partner);
            engine.Rate(call, med, CustomerPartner(partner));

            assertEquals(0, ExpectedRounding(rate, Duration).compareTo(call.RoundedDuration),
                    "plan shape: " + shape.label() + " -> got " + call.RoundedDuration);
        }
    }

    /** A pulse must round UP to a whole multiple of itself — a structural property, independent of the oracle. */
    @Test
    void A_configured_pulse_always_yields_a_whole_multiple_of_itself() {
        int partner = 400, plan = 500;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            if (shape.resolution() <= 0 || shape.minDurationSec() < 0) continue;   // no pulse applied by rule
            cdr call = CallOf(Duration, partner);
            BasicCharge.Default().Rate(call, ResellerTenant(partner, plan, shape), CustomerPartner(partner));

            assertEquals(0, call.RoundedDuration.remainder(BigDecimal.valueOf(shape.resolution())).signum(),
                    shape.label() + ": " + call.RoundedDuration + " is not a multiple of " + shape.resolution());

            // Rounding UP past the actual duration is only guaranteed when the plan actually rounds up. A
            // floor-only plan (threshold 1, unreachable by a fraction) legitimately lands BELOW the actual
            // duration — that is the rule, not a defect.
            if (shape.minDurationSec() == 0f)
                assertTrue(call.RoundedDuration.compareTo(Duration) >= 0,
                        shape.label() + ": an always-ceil plan must never land below the actual duration");
        }
    }

    // ---------------------------------------------------------------- 3. different reseller, different result

    /** Requirement: a reseller on a different plan naturally gets a different RoundedDuration for the same call. */
    @Test
    void Two_resellers_on_different_plans_get_different_results_for_the_same_call() {
        cdr callA = CallOf(Duration, 601);
        cdr callB = CallOf(Duration, 602);

        BasicCharge.Default().Rate(callA, ResellerTenant(601, 701, Shapes.get(3)), CustomerPartner(601)); // 15s pulse
        BasicCharge.Default().Rate(callB, ResellerTenant(602, 702, Shapes.get(5)), CustomerPartner(602)); // 60s pulse

        assertNotEquals(0, callA.RoundedDuration.compareTo(callB.RoundedDuration),
                "different plan configuration must produce a different rounded duration");
    }

    // ---------------------------------------------------------------- 4. tiers

    /**
     * Tier 1 -&gt; Tier N: each tier is its own tenant with its own partner, assignment and plan, so each leg of
     * the same call rounds by ITS OWN plan. Nothing here is shared but the duration.
     */
    @Test
    void Every_tier_in_the_chain_rounds_by_its_own_assigned_plan() {
        record Tier(int partner, int plan, PlanShape shape) {}
        var chain = List.of(
                new Tier(261, 801, Shapes.get(5)),    // tier 1 — 60s pulse
                new Tier(262, 802, Shapes.get(3)),    // tier 2 — 15s pulse
                new Tier(263, 803, Shapes.get(1)));   // tier 3 — per-second

        var results = new ArrayList<BigDecimal>();
        for (Tier tier : chain) {
            MediationContext med = ResellerTenant(tier.partner(), tier.plan(), tier.shape());
            var engine = BasicCharge.Default();
            Rateext rate = engine.MatchCustomerRate(CallOf(Duration, tier.partner()), med,
                    CustomerPartner(tier.partner())).Rate();
            assertEquals(tier.plan(), rate.idrateplan.intValue(), "tier must resolve its OWN plan");

            cdr leg = CallOf(Duration, tier.partner());
            engine.Rate(leg, med, CustomerPartner(tier.partner()));

            assertEquals(0, ExpectedRounding(rate, Duration).compareTo(leg.RoundedDuration),
                    "tier partner " + tier.partner() + " must round by plan " + tier.plan());
            results.add(leg.RoundedDuration);
        }
        assertEquals(results.size(), results.stream().map(BigDecimal::stripTrailingZeros).distinct().count(),
                "these three tier configurations must each produce a distinct rounded duration");
    }

    // ---------------------------------------------------------------- 5. the amount path is untouched

    /**
     * Across the whole matrix, every financial output must still equal what {@code A2ZRater.Rate} produces on
     * its own for the SAME matched rate — the stamp may not perturb amount or Quantity.
     *
     * <p>{@code cdr.Duration1} is deliberately NOT asserted here: it is no longer the amount path's working
     * duration on a reseller tier (it is the RATED duration —
     * {@link ResellerBilledDurationTests}), while {@code Quantity} still is, which is the separation that
     * matters and is pinned below.
     */
    @Test
    void Amount_and_quantity_are_unchanged_across_every_plan_shape() {
        int partner = 900, plan = 950;
        for (PlanShape shape : Shapes) {
            partner++; plan++;
            MediationContext med = ResellerTenant(partner, plan, shape);
            var engine = BasicCharge.Default();
            Rateext rate = engine.MatchCustomerRate(CallOf(Duration, partner), med, CustomerPartner(partner)).Rate();

            A2ZRateResult expected = A2ZRater.Rate(rate, Duration, med.DicRatePlan, med.BillingSpans,
                    med.MaxDecimalPrecision);

            cdr call = CallOf(Duration, partner);
            List<acc_chargeable> chargeables = engine.Rate(call, med, CustomerPartner(partner));
            assertEquals(1, chargeables.size(), shape.label() + ": customer leg only, no cost leg");
            acc_chargeable ch = chargeables.get(0);

            assertEquals(0, expected.Amount().compareTo(ch.BilledAmount), shape.label() + ": BilledAmount moved");
            assertEquals(0, expected.Amount().compareTo(call.InPartnerCost), shape.label() + ": InPartnerCost moved");
            assertEquals(0, expected.BilledDurationSec().compareTo(ch.Quantity), shape.label() + ": Quantity moved");
        }
    }

    /**
     * The surcharge/initial-period setting belongs to the AMOUNT path: two plans identical but for
     * {@code SurchargeTime} must round identically, even though they charge differently.
     */
    @Test
    void Surcharge_configuration_does_not_leak_into_the_rounding() {
        var withSurcharge = new PlanShape("15s pulse + 60s initial period", 15, 0.1f, 60);
        var without = new PlanShape("15s pulse, no initial period", 15, 0.1f, 0);

        cdr a = CallOf(Duration, 1001);
        cdr b = CallOf(Duration, 1002);
        var engine = BasicCharge.Default();
        var medA = ResellerTenant(1001, 1101, withSurcharge);
        var medB = ResellerTenant(1002, 1102, without);
        var chA = engine.Rate(a, medA, CustomerPartner(1001));
        var chB = engine.Rate(b, medB, CustomerPartner(1002));

        assertEquals(0, a.RoundedDuration.compareTo(b.RoundedDuration),
                "SurchargeTime must not change the rounded duration");

        // ...and on the surcharge plan the two paths visibly disagree: the amount path's working duration is
        // the legacy 0 past the initial period, while the rounded duration is the plan's real rounding.
        assertEquals(0, BigDecimal.ZERO.compareTo(chA.get(0).Quantity),
                "the surcharge plan's Quantity is the legacy 0 — untouched by this feature");
        assertTrue(a.RoundedDuration.signum() > 0,
                "...while RoundedDuration is the plan's genuine rounding, proving the two paths are separate");
        // On the no-surcharge plan the amount path takes the ordinary branch, where its working duration IS the
        // plan's rounding — so the two agree there. The feature changes neither.
        assertEquals(0, chB.get(0).Quantity.compareTo(b.RoundedDuration),
                "without a surcharge the amount path's duration and the rounded duration coincide");
    }

    // ---------------------------------------------------------------- 6. guards

    /**
     * A null {@code DurationSec} must leave the column NULL. Exercised on {@code FamilyStamp} directly: the
     * amount path dereferences the duration and throws first, so this cannot be driven through a family.
     */
    @Test
    void Null_duration_leaves_the_column_null() {
        cdr call = CallOf(null, 262);
        Rateext rate = TestData.Ra(880, "0.50").resolution(1).minDurationSec(1f).billingspan(60).idRatePlan(1).rex();

        FamilyStamp.StampLeg(call, rate, AssignmentDirection.Customer,
                new A2ZRateResult(BigDecimal.ZERO, BigDecimal.ZERO), MediationContext.Empty);

        assertNull(call.RoundedDuration, "a genuinely NULL DurationSec must stay NULL, not become 0");
    }

    @Test
    void Zero_duration_with_a_matched_rate_is_zero() {
        cdr call = CallOf(BigDecimal.ZERO, 262);
        BasicCharge.Default().Rate(call, ResellerTenant(262, 101, Shapes.get(0)), CustomerPartner(262));
        assertEquals(0, BigDecimal.ZERO.compareTo(call.RoundedDuration));
    }

    /** The supplier leg must not stamp it — the cost families own that field wherever they run. */
    @Test
    void Supplier_leg_does_not_stamp_it() {
        cdr call = CallOf(Duration, 262);
        call.OutPartnerId = 74;
        Rateext rate = TestData.Ra(880, "0.50").resolution(1).minDurationSec(0.1f).billingspan(60).idRatePlan(1).rex();

        new SfA2Z().Charge(rate, call, 10, AssignmentDirection.Supplier, MediationContext.Empty);

        assertNull(call.RoundedDuration, "customer direction only");
    }

    /** SF1 (base A2Z) shares FamilyStamp, so it must behave identically to SF10. */
    @Test
    void Base_a2z_family_stamps_it_too() {
        Rateext rate = TestData.Ra(880, "0.50").resolution(15).minDurationSec(0.1f).billingspan(60).idRatePlan(1).rex();
        cdr call = CallOf(Duration, 262);

        new SfA2Z().Charge(rate, call, 10, AssignmentDirection.Customer, MediationContext.Empty);

        assertEquals(0, ExpectedRounding(rate, Duration).compareTo(call.RoundedDuration));
    }

    // ---------------------------------------------------------------- 7. admin tenant unchanged

    /**
     * ADMIN / main-tenant guard: where the SG10 idService=20 vendor-cost leg exists it still runs AFTER the
     * rating rules and assigns RoundedDuration last, so a tenant that has one keeps exactly the value it had
     * before this change — the customer plan's rounding must NOT win there.
     */
    @Test
    void Sg10_icx_cost_leg_still_wins_when_the_tenant_has_one() {
        var f = TestData.fixture();
        // NOTE: the customer plan and the ICX cost plan must be DIFFERENT plan ids. Sharing one id puts both
        // rate rows in the same plan bucket under the same prefix, so the customer match can return the cost
        // row — which silently invalidates the very thing this test is checking.
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0,
                TestData.Ra(8801712, "1.0").resolution(60).minDurationSec(1f).billingspan(60).idRatePlan(7));
        f.tup(20, AssignmentDirection.None.value, null, null, 0,
                TestData.Ra(8801712, "0.40").billingspan(60).idRatePlan(8));
        MediationContext med = f.mediation();

        cdr call = new cdr();
        call.InPartnerId = 5;
        call.DurationSec = Duration;
        call.OriginatingCalledNumber = "8801712345678";
        call.TerminatingCalledNumber = "8801712345678";

        var engine = BasicCharge.Default();
        Rateext customerRate = engine.MatchCustomerRate(call, med, CustomerPartner(5)).Rate();
        BigDecimal ifCustomerPlanHadWon = ExpectedRounding(customerRate, Duration);   // 60s pulse

        var chargeables = engine.Rate(call, med, CustomerPartner(5));

        assertEquals(2, chargeables.size(), "customer leg + ICX cost leg");
        assertNotEquals(0, ifCustomerPlanHadWon.compareTo(call.RoundedDuration),
                "the customer plan's rounding must NOT win where a cost leg exists");
        assertEquals(0, HundredMsRounding(Duration).compareTo(call.RoundedDuration),
                "the ICX cost leg assigns RoundedDuration last — admin-tenant behaviour is unchanged");
    }

    /** The ICX cost leg's own fixed rule (legacy {@code HundredMsDuration}): ceil at a 100ms fraction, no pulse. */
    private static BigDecimal HundredMsRounding(BigDecimal actual) {
        BigDecimal floor = actual.setScale(0, RoundingMode.FLOOR);
        return actual.subtract(floor).compareTo(new BigDecimal("0.1")) >= 0
                ? actual.setScale(0, RoundingMode.CEILING) : floor;
    }
}
