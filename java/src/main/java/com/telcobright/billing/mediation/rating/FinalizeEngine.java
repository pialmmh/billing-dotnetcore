package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The post-call charge across the call's tiers — the COMPUTE body of {@code FinalizeAndSummarize}.
 * Mirrors {@link MaxRateEngine}: iterate the per-tier inputs, settle each, return the map keyed by
 * dbName. Each tier settles the customer leg (+ the supplier leg for admin {@code TierMode.Full})
 * via {@link BasicCharge}. It is PURE COMPUTE (no DB): summaries are now OUTBOX-only and owned by the
 * standalone summary-service, so finalize neither loads nor writes any summary — it only returns the
 * per-tier settlements (the routesphere mem-ledger consumer applies them).
 */
public final class FinalizeEngine {
    private final BasicCharge _basicCharge;

    public FinalizeEngine(BasicCharge basicCharge) {
        this._basicCharge = basicCharge;
    }

    public static FinalizeEngine Default() {
        return new FinalizeEngine(BasicCharge.Default());
    }

    public FinalizeResult Finalize(FinalizeFacts facts, List<FinalizeTierInput> chain) {
        if (chain.isEmpty())
            return FinalizeResult.Fail("unknown tenant '" + facts.Tenant() + "'");

        var settlements = new LinkedHashMap<String, TierSettlement>(chain.size());
        BigDecimal total = BigDecimal.ZERO;
        for (var tier : chain) {
            var settlement = SettleTier(facts, tier);
            settlements.put(tier.DbName(), settlement);
            if (settlement.Error() == null) total = total.add(settlement.Charged());
        }

        var failing = settlements.values().stream().filter(s -> s.Error() != null).findFirst().orElse(null);
        return new FinalizeResult(failing == null, failing != null ? failing.Error() : "", settlements, total);
    }

    private TierSettlement SettleTier(FinalizeFacts facts, FinalizeTierInput tier) {
        var thisCdr = BuildCdr(facts, tier);
        var customer = _basicCharge.Compute(thisCdr, AssignmentDirection.Customer, tier.Mediation(), tier.Partners());
        if (customer == null)
            return TierSettlement.Unrated(tier.DbName(), tier.PartnerId());

        // Admin (FULL) tiers also charge the supplier leg (the cost paid to the out-partner); reseller
        // tiers are customer-only. The supplier leg reads the InPartnerCost set above, so it runs on the
        // SAME cdr, after the customer leg. (Null when there's no supplier tuple, e.g. SG11.)
        acc_chargeable supplier = tier.Mode() == TierMode.Full
                ? _basicCharge.Compute(thisCdr, AssignmentDirection.Supplier, tier.Mediation(), tier.Partners())
                : null;
        BigDecimal supplierCost = supplier != null ? supplier.BilledAmount : BigDecimal.ZERO;

        // The reserved uom decides how the charge lands: package units (consumed minutes) vs cash (BDT).
        var reserved = tier.Reserved();
        String uom = reserved != null && reserved.Uom() != null ? reserved.Uom() : "BDT";
        boolean isCash = uom.equalsIgnoreCase("BDT");
        BigDecimal billedAmount = customer.BilledAmount;
        BigDecimal packageAmount = isCash ? BigDecimal.ZERO
                : customer.Quantity.divide(BigDecimal.valueOf(60), MathContext.DECIMAL128).setScale(8, RoundingMode.HALF_EVEN);
        BigDecimal inPartnerCost = isCash ? billedAmount : BigDecimal.ZERO;
        BigDecimal charged = isCash ? billedAmount : packageAmount;

        return new TierSettlement(tier.DbName(), tier.PartnerId(), customer.servicegroup, customer.servicefamily,
                uom, charged, packageAmount, inPartnerCost,
                customer.TaxAmount1 != null ? customer.TaxAmount1 : BigDecimal.ZERO, supplierCost,
                customer.Prefix, null);
    }

    /**
     * Build the per-tier cdr from the call facts (dotnet owns the cdr shape, per the contract).
     * Both numbers are set; the SG detector picks the terminating (out) or originating (in) one. The
     * charged partner at this tier is the in-partner for the customer leg.
     */
    private static cdr BuildCdr(FinalizeFacts facts, FinalizeTierInput tier) {
        cdr c = new cdr();
        c.InPartnerId = tier.PartnerId();
        c.OutPartnerId = facts.OutPartnerId();
        c.OriginatingCallingNumber = facts.CallingNumber();
        c.TerminatingCalledNumber = facts.CalledNumber();
        c.DurationSec = BigDecimal.valueOf(facts.Billsec());
        c.SwitchId = facts.SwitchId();
        c.IncomingRoute = facts.IncomingRoute();
        c.OutgoingRoute = facts.OutgoingRoute();
        c.AnswerTime = facts.AnswerTime();
        c.StartTime = facts.AnswerTime();
        c.ChargingStatus = facts.Answered() ? 1 : 0;
        return c;
    }
}
