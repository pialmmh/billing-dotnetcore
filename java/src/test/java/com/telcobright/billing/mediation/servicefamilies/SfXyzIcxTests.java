package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SG15 International-Outgoing (Xyz-ICX) per-call parity against the LIVE production cdr. Each case feeds the
 * exact production inputs — the plan-117 rate row for the matched international prefix (incl. the surcharge
 * = minimum-initial-period fields) and the call's OWN stored {@code UsdRateY} (isolating the calc from later
 * USD-table drift) — and asserts every Xyz output the production sink stored (X, Y, Z, invoice/BilledAmount,
 * Tax2), plus the chargeable field mapping the SG15 summary reads.
 *
 * <p>Production reference (telcobright.cdr, ServiceGroup=15, plan 117 "Outgoing XYZ @IGW New Rate"):
 * <pre>
 *   dest 0097180044444 (UAE 9718): dur 29.16 -> X 5.50  Y 0.04  usd 122  Z 0.67  billed 0.10  tax2 0.05
 *   dest 006621465999  (TH  662) : dur 225.26-> X 22.13 Y 0.08  usd 122  Z 12.01 billed 1.80  tax2 0.90
 *   dest 006621465999  (TH  662) : dur 77.60 -> X 2.40  Y 0.01  usd 122  Z 1.30  billed 0.20  tax2 0.10
 * </pre>
 */
class SfXyzIcxTests {

    private static final SfXyzIcx SG15 = new SfXyzIcx();

    // Plan 117 billing span is TF_min (60s); TestData.planMap(7) carries BillingSpan=TF_min so the span resolves to 60.
    private static MediationContext med(String month, String usdRate) {
        MediationContext m = new MediationContext();
        m.MaxDecimalPrecision = 8;
        m.DicRatePlan = TestData.planMap(7);
        m.BillingSpans = TestData.billingSpans();
        m.UsdToBdtByMonth.put(month, new BigDecimal(usdRate));
        return m;
    }

    /** A plan-117 international rate row (the Xyz rate: x=rateamount, y=OtherAmount1 USD, share=OtherAmount2, btrc=OtherAmount3). */
    private static Rateext xyzRate(String prefix, String xRate, String yRateUsd, int surchargeTime, int resolution, String cc) {
        Rateext r = new Rateext();
        r.id = 1;
        r.Prefix = prefix;
        r.rateamount = new BigDecimal(xRate);
        r.OtherAmount1 = new BigDecimal(yRateUsd);
        r.OtherAmount2 = new BigDecimal("15");   // margin-share %
        r.OtherAmount3 = new BigDecimal("50");   // BTRC rev-share %
        r.SurchargeTime = surchargeTime;
        r.SurchargeAmount = BigDecimal.ZERO;
        r.Resolution = resolution;
        r.MinDurationSec = 0.1f;
        r.idrateplan = 7;                         // TF_min span
        r.CountryCode = cc;
        r.billingspan = null;
        r.startdate = LocalDateTime.of(1, 1, 1, 0, 0);
        r.Category = (byte) 1;
        r.SubCategory = (byte) 1;
        return r;
    }

    private static cdr answered(String dest, String durSec) {
        cdr c = new cdr();
        c.UniqueBillId = "test-sg15";
        c.OriginatingCalledNumber = dest;
        c.DurationSec = new BigDecimal(durSec);
        c.ChargingStatus = 1;
        c.AnswerTime = LocalDateTime.of(2026, 7, 15, 10, 0);
        c.StartTime = c.AnswerTime;
        return c;
    }

    private static void eq(String label, String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), label + " expected " + expected + " got " + actual);
    }

    @Test
    void Uae_9718_30s_matches_production() {
        // SurchargeTime=15: billed duration = ceil(29.16)=30 -> excl 15 -> x = 15*22/60 = 5.50, y = 15*0.1583/60 = 0.039575
        cdr c = answered("0097180044444", "29.16");
        acc_chargeable ch = SG15.Charge(xyzRate("9718", "22", "0.1583", 15, 15, "971"), c, 15,
                AssignmentDirection.Customer, med("2026-07", "122.00"));

        eq("RoundedDuration", "30", c.RoundedDuration);
        eq("XAmount", "5.50", c.XAmount);
        eq("YAmount(USD)", "0.039575", c.YAmount);
        eq("UsdRateY", "122.00", c.UsdRateY);
        eq("ZAmount", "0.67185", c.ZAmount);            // 5.50 - 0.039575*122
        eq("BilledAmount(invoice=15% of Z)", "0.1007775", ch.BilledAmount);
        eq("RevenueIcxOut", "0.1007775", c.RevenueIcxOut);
        eq("Tax2(BTRC 50% of invoice)", "0.05038875", c.Tax2);
        // chargeable field mapping the SG15 summary reads
        eq("chg.OtherAmount1=x", "5.50", ch.OtherAmount1);
        eq("chg.OtherAmount2=y", "0.039575", ch.OtherAmount2);
        eq("chg.OtherAmount3=z", "0.67185", ch.OtherAmount3);
        eq("chg.OtherDecAmount1=xRate", "22", ch.OtherDecAmount1);
        eq("chg.OtherDecAmount2=yRate", "0.1583", ch.OtherDecAmount2);
        eq("chg.OtherDecAmount3=usdRate", "122.00", ch.OtherDecAmount3);
        org.junit.jupiter.api.Assertions.assertTrue(ch.servicegroup == 15, "servicegroup=15");
        org.junit.jupiter.api.Assertions.assertTrue(ch.servicefamily == 7, "servicefamily=7 (SfXyzIcx)");
        org.junit.jupiter.api.Assertions.assertTrue(ch.assignedDirection == 1, "assignedDirection=1 (customer)");
    }

    @Test
    void Thailand_662_long_matches_production() {
        // SurchargeTime=60: billed excl = ceil(225.26)=226 - 60 = 166 -> x = 166*8/60 = 22.13333, y = 166*0.03/60 = 0.083
        cdr c = answered("006621465999", "225.26");
        acc_chargeable ch = SG15.Charge(xyzRate("662", "8", "0.03", 60, 1, "66"), c, 15,
                AssignmentDirection.Customer, med("2026-07", "122.00"));
        eq("XAmount", "22.13333333", c.XAmount);
        eq("YAmount(USD)", "0.083", c.YAmount);
        eq("ZAmount", "12.00733333", c.ZAmount);           // 22.13333333 - 0.083*122
        eq("BilledAmount", "1.80110000", ch.BilledAmount);  // 12.00733333 * 15%
        eq("Tax2", "0.90055000", c.Tax2);                   // 1.8011 * 50%
    }

    @Test
    void Thailand_662_short_matches_production() {
        // ceil(77.60)=78 - 60 = 18 -> x = 18*8/60 = 2.40, y = 18*0.03/60 = 0.009
        cdr c = answered("006621465999", "77.60");
        acc_chargeable ch = SG15.Charge(xyzRate("662", "8", "0.03", 60, 1, "66"), c, 15,
                AssignmentDirection.Customer, med("2026-07", "122.00"));
        eq("XAmount", "2.40", c.XAmount);
        eq("YAmount(USD)", "0.009", c.YAmount);
        eq("ZAmount", "1.302", c.ZAmount);                  // 2.40 - 0.009*122
        eq("BilledAmount", "0.1953", ch.BilledAmount);      // 1.302 * 15%
        eq("Tax2", "0.09765", c.Tax2);                      // 0.1953 * 50%
    }

    @Test
    void Missing_usd_rate_fails_loud_not_zero() {
        cdr c = answered("0097180044444", "29.16");
        MediationContext noUsd = med("2099-01", "122.00");   // no conversion for the call's 2026-07 month
        try {
            SG15.Charge(xyzRate("9718", "22", "0.1583", 15, 15, "971"), c, 15, AssignmentDirection.Customer, noUsd);
            org.junit.jupiter.api.Assertions.fail("expected MissingUsdRateException — must never silently zero-rate");
        } catch (SfXyzIcx.MissingUsdRateException expected) {
            // correct: an answered SG15 call with no USD conversion fails loud
        }
    }
}
