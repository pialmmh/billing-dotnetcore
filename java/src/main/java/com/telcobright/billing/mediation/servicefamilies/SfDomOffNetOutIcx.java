package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * SF 20 — the ICX/ANS COST leg for SG 10 (legacy {@code SfDomOffNetOutIcx}). It is a service-wide cost applied
 * to every domestic-outgoing call from ONE {@code idService = 20} rate row (resolved by the SG10 ICX pass in
 * {@link com.telcobright.billing.mediation.rating.BasicCharge}), not a per-partner leg. From that single rate:
 * <ul>
 *   <li><b>ICX base</b> = duration × ({@code rateamount} − {@code OtherAmount1} IOF) / 60; its BTRC VAT = base ×
 *       {@code OtherAmount3} → {@code cdr.Tax2}. {@code cdr.CostIcxIn} = base + VAT (VAT-INCLUSIVE), while
 *       {@code cdr.OutPartnerCost} = base (VAT-EXCLUSIVE) — the summary reads {@code suppliercost} off it.</li>
 *   <li><b>ANS base</b> = duration × {@code OtherAmount2} (the ANS rate) / 60; its VAT = base ×
 *       {@code OtherAmount3} → {@code cdr.ZAmount}. {@code cdr.CostAnsIn} = base + VAT (VAT-INCLUSIVE).</li>
 * </ul>
 *
 * <p><b>Duration:</b> the leg pulses the billable seconds through {@code HundredMsDuration} (ceil if the fraction
 * is ≥ 0.1s, else floor) exactly like legacy — the value the production cdr carries in {@code RoundedDuration}.
 *
 * <p><b>VAT model (verified against the live .110 production cdr):</b> production runs legacy with the
 * {@code *AmountWithVat} assignments active, so {@code CostIcxIn}/{@code CostAnsIn} include the 15% BTRC VAT
 * (effective 0.046 / 0.115 per min) while {@code OutPartnerCost} stays the bare 0.04 ICX base, and
 * {@code Tax2}/{@code ZAmount} carry the VAT portions.
 *
 * <p>Legacy {@code SfDomOffNetOutIcx.Execute} returned no chargeable (it only stamped the cdr cost fields, which
 * the summary reads as {@code suppliercost}/{@code anscost}). This port ALSO returns a supplier-direction cost
 * chargeable so the ICX cost is visible in {@code acc_chargeable} and the shadow reconciliation can compare it.
 * The customer revenue leg (SF10) is untouched; {@code CdrBatchResult.TotalCharged} sums the customer chargeable
 * only, so this cost leg does not affect customer revenue.
 */
public final class SfDomOffNetOutIcx implements IServiceFamily {
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    @Override public int Id() { return 20; }

    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        int maxDec = mediation.MaxDecimalPrecision;
        // finalDuration = HundredMsDuration(rawDur): legacy pulses the billable seconds (ceil if the fractional
        // part >= 0.1s, else floor) — the production cdr.RoundedDuration column carries exactly this value.
        BigDecimal dur = HundredMsDuration(cdr.DurationSec);
        BigDecimal iof = rate.OtherAmount1 != null ? rate.OtherAmount1 : BigDecimal.ZERO;
        BigDecimal ansRate = rate.OtherAmount2 != null ? rate.OtherAmount2 : BigDecimal.ZERO;
        BigDecimal btrc = rate.OtherAmount3 != null ? rate.OtherAmount3 : BigDecimal.ZERO;

        // ICX: (rate - IOF) per minute over the pulsed duration; BTRC VAT on top.
        BigDecimal icxRate = rate.rateamount.subtract(iof);
        BigDecimal icxAmount = ChargeableBuilder.Round(dur.multiply(icxRate).divide(SIXTY, MathContext.DECIMAL128), maxDec);
        BigDecimal icxVat = ChargeableBuilder.Round(icxAmount.multiply(btrc), maxDec);
        BigDecimal icxAmountWithVat = ChargeableBuilder.Round(icxAmount.add(icxVat), maxDec);
        // ANS: OtherAmount2 per minute over the pulsed duration; its own BTRC VAT.
        BigDecimal ansAmount = ChargeableBuilder.Round(dur.multiply(ansRate).divide(SIXTY, MathContext.DECIMAL128), maxDec);
        BigDecimal ansVat = ChargeableBuilder.Round(ansAmount.multiply(btrc), maxDec);
        BigDecimal ansAmountWithVat = ChargeableBuilder.Round(ansAmount.add(ansVat), maxDec);

        // stamp the cdr cost fields to match the LIVE production sink (verified against the .110 cdr):
        //   CostIcxIn / CostAnsIn are VAT-INCLUSIVE (legacy icxAmountWithVat / ansAmountWithVat), while
        //   OutPartnerCost stays the VAT-EXCLUSIVE ICX base (eff rate 0.04 vs 0.046 on CostIcxIn). Tax2 / ZAmount
        //   hold the VAT portions. The summary reads suppliercost <- OutPartnerCost, tax2 <- Tax2.
        cdr.CostIcxIn = icxAmountWithVat;
        cdr.OutPartnerCost = icxAmount;
        cdr.Tax2 = icxVat;
        cdr.CostAnsIn = ansAmountWithVat;
        cdr.ZAmount = ansVat;
        cdr.RoundedDuration = dur;
        cdr.SupplierRate = rate.rateamount;
        cdr.MatchedPrefixSupplier = rate.Prefix;
        cdr.CountryCode = rate.CountryCode;

        // supplier-direction cost chargeable (BilledAmount = ICX cost, TaxAmount1 = ICX BTRC VAT) so the cost is
        // recorded in acc_chargeable and rollupable; ANS lives on the cdr (CostAnsIn/ZAmount).
        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), AssignmentDirection.Supplier,
                icxAmount, dur, icxVat, mediation);
    }

    /**
     * Legacy {@code PrefixMatcher.HundredMsDuration}: round the billable seconds to a whole second — ceil when
     * the fractional part is at least {@code minDurationSec} (0.1s = 100ms), otherwise floor. Unlike the customer
     * leg's {@code GetA2ZDuration} it applies NO Resolution/pulse multiple. 0 stays 0.
     */
    private static BigDecimal HundredMsDuration(BigDecimal actualDurationSec) {
        if (actualDurationSec == null || actualDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal minDurationSec = new BigDecimal("0.1");
        BigDecimal floorDuration = actualDurationSec.setScale(0, RoundingMode.FLOOR);
        BigDecimal miliSecPart = actualDurationSec.subtract(floorDuration);
        if (miliSecPart.compareTo(minDurationSec) >= 0) {
            return actualDurationSec.setScale(0, RoundingMode.CEILING);
        }
        return floorDuration;
    }
}
