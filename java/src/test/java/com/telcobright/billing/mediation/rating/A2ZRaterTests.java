package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faithful A2Z math (ported from ExecuteA2ZRating + GetA2ZDuration + GetA2ZAmountWith[Out]SurCharge): pulse,
 * the ms-threshold, the SURCHARGE = minimum-initial-period model INCLUDING the legacy {@code finalDuration}
 * quirk (it stays 0 when dur &gt; SurchargeTime), the OtherAmount9 fraction-ceiling, and the billing span
 * resolved from the rate then (if absent) from the rate plan.
 */
class A2ZRaterTests {

    private static final Map<String, rateplan> Plan7 = TestData.planMap(7);   // BillingSpan = TF_min (60s)
    private static final Map<String, enumbillingspan> Spans = TestData.billingSpans();

    private static A2ZRateResult Rate(Rateext rate, String dur) {
        return A2ZRater.Rate(rate, new BigDecimal(dur), Plan7, Spans, 8);
    }

    // BigDecimal.equals is scale-sensitive; compare each component by VALUE (compareTo).
    private static void T(String expBilledDurationSec, BigDecimal expAmount, A2ZRateResult r) {
        assertEquals(0, new BigDecimal(expBilledDurationSec).compareTo(r.BilledDurationSec()),
                "billed duration; got " + r.BilledDurationSec());
        assertEquals(0, expAmount.compareTo(r.Amount()), "amount; got " + r.Amount());
    }

    @Test
    void Per_minute_no_pulse() {
        var rate = TestData.Ra(1, "1.0").rex();   // Resolution 0, SurchargeTime 0, billing span via plan = 60
        T("60", new BigDecimal("1.0"), Rate(rate, "60"));
        T("30", new BigDecimal("0.5"), Rate(rate, "30"));
        // ceil to 13; amount = round(13/60, 8)
        T("13", new BigDecimal("13").divide(new BigDecimal("60"), 8, RoundingMode.HALF_EVEN), Rate(rate, "12.07"));
    }

    @Test
    void Per_minute_pulse_rounds_up() {
        var rate = TestData.Ra(1, "1.0").resolution(60).rex();
        T("60", new BigDecimal("1.0"), Rate(rate, "60"));
        T("120", new BigDecimal("2.0"), Rate(rate, "61"));   // 61 -> next minute
    }

    @Test
    void Surcharge_is_a_minimum_initial_period_not_a_flat_fee() {
        var rate = TestData.Ra(1, "1.0").resolution(6).surchargeTime(30).rex();
        // 12.07s <= 30 => the whole minimum initial period bills: 30s, amount 30/60 = 0.5
        T("30", new BigDecimal("0.5"), Rate(rate, "12.07"));
        // 42s > 30 => surcharge(30/60=0.5) + pulse6(after 12s -> 12)=12/60=0.2 => 0.7; finalDuration STAYS 0 (quirk)
        T("0", new BigDecimal("0.7"), Rate(rate, "42"));
    }

    @Test
    void Min_duration_ms_threshold() {
        var rate = TestData.Ra(1, "1.0").minDurationSec(0.1f).rex();
        // frac .05 < .1 -> floor
        assertEquals(0, new BigDecimal("60").compareTo(A2ZRater.GetA2ZDuration(new BigDecimal("60.05"), rate)));
        // frac .15 >= .1 -> ceil
        assertEquals(0, new BigDecimal("61").compareTo(A2ZRater.GetA2ZDuration(new BigDecimal("60.15"), rate)));
    }

    @Test
    void OtherAmount9_ceilings_the_amount() {
        // 13s @ 1.0/min = 0.21666667 (round 8); ceiling at fractional position 2 => 0.22
        var rate = TestData.Ra(1, "1.0").otherAmount9(2f).rex();
        T("13", new BigDecimal("0.22"), Rate(rate, "12.07"));
    }

    @Test
    void Billing_span_from_the_rate_overrides_the_rate_plan() {
        // rate.billingspan = 30 -> 60s @ 1.0 / 30 = 2.0 (the rate plan's TF_min/60 is NOT consulted)
        var rate = TestData.Ra(1, "1.0").billingspan(30).rex();
        T("60", new BigDecimal("2.0"), Rate(rate, "60"));
    }

    @Test
    void Billing_span_falls_back_to_the_rate_plan_uom() {
        // rate.billingspan null -> resolve via rate plan's BillingSpan uom (TF_30s -> 30): 60s @ 1.0 / 30 = 2.0
        var rate = TestData.Ra(1, "1.0").rex();   // billingspan null, idrateplan 7
        rateplan rp = new rateplan();
        rp.id = 7;
        rp.BillingSpan = "TF_30s";
        Map<String, rateplan> dic = new HashMap<>();
        dic.put("7", rp);
        enumbillingspan s = new enumbillingspan();
        s.ofbiz_uom_Id = "TF_30s";
        s.value = 30;
        Map<String, enumbillingspan> spans = new HashMap<>();
        spans.put("TF_30s", s);

        var r = A2ZRater.Rate(rate, new BigDecimal("60"), dic, spans, 8);
        assertEquals(0, new BigDecimal("60").compareTo(r.BilledDurationSec()));
        assertEquals(0, new BigDecimal("2.0").compareTo(r.Amount()));
    }
}
