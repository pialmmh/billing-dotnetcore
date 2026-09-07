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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code cdr.RoundedDuration} on the CUSTOMER leg, derived from that tier's own assigned rate plan.
 *
 * <p>Context (2026-09-07): reseller schemas were 100% NULL in this column because on SG10 it used to be a
 * by-product of the idService=20 vendor-cost leg ({@link SfDomOffNetOutIcx}), which a customer-revenue-only
 * tenant has no config for. {@code FamilyStamp} now derives it on the customer leg via
 * {@link A2ZRater#GetA2ZDuration} from the SAME {@code Rateext} the amount was rated with — the row resolved
 * through that tier's partner-keyed tuple -&gt; rateassign -&gt; rate plan.
 *
 * <p>The load-bearing property is that it is NOT {@code a2z.BilledDurationSec()}, which A2ZRater deliberately
 * leaves at 0 for any call longer than {@code SurchargeTime}. Rounding is re-derived from the rate config; the
 * amount path is untouched ({@link #Billing_amounts_are_untouched_by_the_stamp}).
 *
 * <p><b>MinDurationSec is a ROUND-UP THRESHOLD on the fractional second, not a minimum duration.</b> The
 * fractional part is always &lt; 1, so a rate carrying {@code MinDurationSec = 1} — which is what the live
 * reseller plan carries — can never round up and always floors. A sub-second threshold (the ICX/SG15 rates use
 * {@code 0.1}) or {@code 0} (always ceil) is what produces a round-up. Both branches are pinned below.
 */
class ResellerRoundedDurationTests {

    private static final MediationContext Med = MediationContext.Empty;   // MaxDecimalPrecision = 8

    /** The live reseller call that exposed the bug: seq 11048973, 93.643s on a 0.50/min plan. */
    private static final BigDecimal LiveDuration = new BigDecimal("93.643");

    /** The reseller's assigned plan, with its rounding knobs left to the caller. 60s initial period as in prod. */
    private static Rateext ResellerRate(int resolution, float minDurationSec) {
        return TestData.Ra(880, "0.50")
                .resolution(resolution).minDurationSec(minDurationSec)
                .surchargeTime(60).surchargeAmount("60")
                .billingspan(60).idRatePlan(1).rex();
    }

    /** Exactly the live production row: Resolution 1, MinDurationSec 1, SurchargeTime 60. */
    private static Rateext LiveResellerRate() {
        return ResellerRate(1, 1f);
    }

    private static cdr CallOf(BigDecimal durationSec) {
        cdr c = new cdr();
        c.DurationSec = durationSec;
        c.InPartnerId = 262;                       // the reseller's own customer
        c.OriginatingCalledNumber = "8801700000000";
        c.TerminatingCalledNumber = "8801700000000";
        return c;
    }

    // ---- 1. the reseller case: customer-only, no vendor-cost leg anywhere in sight ----

    @Test
    void Customer_only_call_gets_a_rounded_duration_without_any_cost_leg() {
        cdr call = CallOf(LiveDuration);
        acc_chargeable ch = new SfA2ZWithVatTax()
                .Charge(LiveResellerRate(), call, 10, AssignmentDirection.Customer, Med);

        assertNotNull(ch);
        assertNotNull(call.RoundedDuration, "a customer-revenue-only tenant must still get RoundedDuration");
        assertNull(call.OutPartnerCost, "no supplier/cost leg ran — this is the reseller shape");
    }

    /** SF1 (base A2Z) shares FamilyStamp, so it must behave identically to SF10. */
    @Test
    void Base_a2z_family_stamps_it_too() {
        cdr call = CallOf(LiveDuration);
        new SfA2Z().Charge(LiveResellerRate(), call, 10, AssignmentDirection.Customer, Med);
        assertEquals(0, new BigDecimal("93").compareTo(call.RoundedDuration));
    }

    // ---- 2. the surcharge case: rounding is re-derived, the amount path keeps its quirked 0 ----

    @Test
    void Surcharge_case_rounds_the_actual_duration_not_the_amount_path() {
        Rateext rate = LiveResellerRate();
        cdr call = CallOf(LiveDuration);

        // what the AMOUNT path independently computes for this call — the value we must NOT reuse
        A2ZRateResult a2z = A2ZRater.Rate(rate, LiveDuration, Med.DicRatePlan, Med.BillingSpans,
                Med.MaxDecimalPrecision);
        assertEquals(0, BigDecimal.ZERO.compareTo(a2z.BilledDurationSec()),
                "A2ZRater leaves finalDuration 0 past SurchargeTime — the legacy quirk this must not inherit");

        acc_chargeable ch = new SfA2ZWithVatTax().Charge(rate, call, 10, AssignmentDirection.Customer, Med);

        // 93.643 -> frac .643 < MinDurationSec 1 -> FLOOR 93 -> pulse 1 leaves 93.
        assertEquals(0, new BigDecimal("93").compareTo(call.RoundedDuration),
                "the LIVE reseller rate (MinDurationSec=1) can never round up: the fraction is always < 1");
        assertEquals(0, A2ZRater.GetA2ZDuration(LiveDuration, rate).compareTo(call.RoundedDuration),
                "RoundedDuration must equal the rate plan's own rounding of the ACTUAL duration");

        // ...and the amount path is untouched by any of it
        assertEquals(0, BigDecimal.ZERO.compareTo(ch.Quantity),
                "Quantity still comes from BilledDurationSec — unchanged");
        assertEquals(0, new BigDecimal("0.775").compareTo(ch.BilledAmount),
                "amount unchanged: surcharge window 0.50 + remainder 33s @ 0.50/min 0.275");
    }

    /**
     * The same call reaches 94 only when the plan carries a SUB-SECOND round-up threshold, the way the ICX and
     * international rates do ({@code MinDurationSec = 0.1}). This is a rate-plan CONFIG lever, not a code path.
     */
    @Test
    void Sub_second_threshold_is_what_rounds_93_643_up_to_94() {
        cdr call = CallOf(LiveDuration);
        new SfA2ZWithVatTax().Charge(ResellerRate(1, 0.1f), call, 10, AssignmentDirection.Customer, Med);
        assertEquals(0, new BigDecimal("94").compareTo(call.RoundedDuration),
                "frac .643 >= threshold .1 -> ceil 94");
    }

    // ---- 3. tiers: the SAME call, each tier's own plan resolution ----

    @Test
    void Tier1_resolution_60_rounds_to_120() {
        cdr call = CallOf(LiveDuration);
        new SfA2ZWithVatTax().Charge(ResellerRate(60, 1f), call, 10, AssignmentDirection.Customer, Med);
        // floor(93.643) = 93; 93 % 60 = 33 > 0 -> (93/60 + 1) * 60 = 120
        assertEquals(0, new BigDecimal("120").compareTo(call.RoundedDuration));
    }

    @Test
    void Tier2_resolution_15_rounds_to_105() {
        cdr call = CallOf(LiveDuration);
        new SfA2ZWithVatTax().Charge(ResellerRate(15, 1f), call, 10, AssignmentDirection.Customer, Med);
        // 93 % 15 = 3 > 0 -> (93/15 + 1) * 15 = 105
        assertEquals(0, new BigDecimal("105").compareTo(call.RoundedDuration));
    }

    /** One call rated down a two-tier chain: each tier stamps ITS OWN plan's rounding, independently. */
    @Test
    void Each_tier_uses_its_own_plans_rounding_rule() {
        cdr tier1Leg = CallOf(LiveDuration);
        cdr tier2Leg = CallOf(LiveDuration);

        new SfA2ZWithVatTax().Charge(ResellerRate(60, 1f), tier1Leg, 10, AssignmentDirection.Customer, Med);
        new SfA2ZWithVatTax().Charge(ResellerRate(15, 1f), tier2Leg, 10, AssignmentDirection.Customer, Med);

        assertEquals(0, new BigDecimal("120").compareTo(tier1Leg.RoundedDuration));
        assertEquals(0, new BigDecimal("105").compareTo(tier2Leg.RoundedDuration));
    }

    // ---- 4. the MinDurationSec branches ----

    @Test
    void Negative_min_duration_means_no_rounding_at_all() {
        cdr call = CallOf(LiveDuration);
        new SfA2ZWithVatTax().Charge(ResellerRate(60, -1f), call, 10, AssignmentDirection.Customer, Med);
        assertEquals(0, LiveDuration.compareTo(call.RoundedDuration),
                "MinDurationSec < 0 returns the actual duration verbatim — the pulse is not applied either");
    }

    @Test
    void Zero_min_duration_always_ceils() {
        cdr call = CallOf(LiveDuration);
        new SfA2ZWithVatTax().Charge(ResellerRate(1, 0f), call, 10, AssignmentDirection.Customer, Med);
        assertEquals(0, new BigDecimal("94").compareTo(call.RoundedDuration));
    }

    // ---- 5. the guards ----

    /**
     * The null-duration guard is exercised on {@code FamilyStamp} DIRECTLY: a null {@code DurationSec} throws
     * inside {@code A2ZRater.Rate} (the amount path dereferences it) long before the stamp is reached, so it
     * cannot be driven through {@code Charge()}. Production never gets there either — ingest dead-letters a
     * record with no {@code durationSec} — but the stamp must not be the thing that turns a NULL into a 0.
     */
    @Test
    void Null_duration_leaves_the_column_null() {
        cdr call = CallOf(null);
        A2ZRateResult noCharge = new A2ZRateResult(BigDecimal.ZERO, BigDecimal.ZERO);

        FamilyStamp.StampLeg(call, LiveResellerRate(), AssignmentDirection.Customer, noCharge);

        assertNull(call.RoundedDuration, "a genuinely NULL DurationSec must stay NULL, not become 0");
    }

    @Test
    void Zero_duration_with_a_matched_rate_is_zero() {
        cdr call = CallOf(BigDecimal.ZERO);
        new SfA2ZWithVatTax().Charge(LiveResellerRate(), call, 10, AssignmentDirection.Customer, Med);
        assertEquals(0, BigDecimal.ZERO.compareTo(call.RoundedDuration));
    }

    /** The supplier leg must not stamp it — the cost families own that field where they run. */
    @Test
    void Supplier_leg_does_not_stamp_it() {
        cdr call = CallOf(LiveDuration);
        call.OutPartnerId = 74;
        new SfA2Z().Charge(LiveResellerRate(), call, 10, AssignmentDirection.Supplier, Med);
        assertNull(call.RoundedDuration, "customer direction only");
    }

    // ---- 6. the amount-invariance and SG10 ICX-ordering pins ----

    /**
     * The stamp is a pure re-derivation: every financial output of the family must still equal what the amount
     * path produces on its own, across the surcharge and plain branches and several pulses.
     */
    @Test
    void Billing_amounts_are_untouched_by_the_stamp() {
        Rateext[] rates = {
                LiveResellerRate(),                                     // surcharge branch, pulse 1
                ResellerRate(15, 1f),                                   // surcharge branch, pulse 15
                TestData.Ra(880, "0.50").resolution(1).minDurationSec(1f)
                        .billingspan(60).idRatePlan(1).rex(),           // no surcharge at all
        };
        for (Rateext rate : rates) {
            A2ZRateResult expected = A2ZRater.Rate(rate, LiveDuration, Med.DicRatePlan, Med.BillingSpans,
                    Med.MaxDecimalPrecision);
            cdr call = CallOf(LiveDuration);
            acc_chargeable ch = new SfA2ZWithVatTax().Charge(rate, call, 10, AssignmentDirection.Customer, Med);

            assertEquals(0, expected.Amount().compareTo(ch.BilledAmount), "BilledAmount must not move");
            assertEquals(0, expected.Amount().compareTo(call.InPartnerCost), "InPartnerCost must not move");
            assertEquals(0, expected.BilledDurationSec().compareTo(ch.Quantity), "Quantity must not move");
            assertEquals(0, expected.BilledDurationSec().compareTo(call.Duration1), "Duration1 must not move");
        }
    }

    /**
     * ADMIN / main-tenant guard: where the SG10 idService=20 vendor-cost leg exists it still runs AFTER the
     * rating rules and assigns RoundedDuration last, so a tenant that has one keeps exactly the value it had
     * before this change. The customer pulse of 60 would give 120; the ICX leg's HundredMsDuration(93.643)
     * gives 94 — and 94 must win.
     */
    @Test
    void Sg10_icx_cost_leg_still_wins_when_the_tenant_has_one() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0,
                TestData.Ra(8801712, "1.0").resolution(60).minDurationSec(1f).billingspan(60).idRatePlan(7));
        f.tup(20, AssignmentDirection.None.value, null, null, 0,
                TestData.Ra(8801712, "0.40").billingspan(60).idRatePlan(7));

        cdr call = new cdr();
        call.InPartnerId = 5;
        call.DurationSec = LiveDuration;
        call.OriginatingCalledNumber = "8801712345678";
        call.TerminatingCalledNumber = "8801712345678";

        Map<Integer, Partner> partners = new HashMap<>();
        partners.put(5, new Partner(5, null, 3));
        var chargeables = BasicCharge.Default().Rate(call, f.mediation(), partners);

        assertEquals(2, chargeables.size(), "customer leg + ICX cost leg");
        assertEquals(0, new BigDecimal("94").compareTo(call.RoundedDuration),
                "the ICX cost leg assigns RoundedDuration last — admin-tenant behaviour is unchanged");
    }
}
