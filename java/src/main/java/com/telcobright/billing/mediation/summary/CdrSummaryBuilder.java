package com.telcobright.billing.mediation.summary;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_03;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_05;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_03;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_05;

/**
 * Builds a per-call AbstractCdrSummary from the cdr + its customer acc_chargeable — the port of legacy
 * {@code CdrSummaryFactory.CreateInstanceWithoutDate} (the common identity/count/duration fields) + the
 * day/hour bucketing + each SG's {@code SetServiceGroupWiseSummaryParams}:
 * <ul>
 * <li>SG10 -&gt; sum_voice_day_03/hr_03 (SfA2ZWithVatTax customer leg).</li>
 * <li>SG11 -&gt; sum_voice_day_02/hr_02 (SfDomOffNetInAns customer leg).</li>
 * </ul>
 * The customer-leg fields come from the customer {@code acc_chargeable}. The supplier fields
 * ({@code suppliercost}/{@code supplierrate}/{@code tax2}) are read off the {@code cdr}, where the SF1
 * supplier leg stamps them under the default SG10 config — so they populate whenever a supplier rate
 * matched (0 otherwise), NOT "always 0". Only {@code vat} ({@code cdr.ZAmount}) and SG10 {@code anscost}
 * ({@code longDecimalAmount1}) are currently left 0 (unwired). Aggregation by {@code GetTupleKey()} + the
 * single-connection write are the persistence slice (deferred); this just builds the summary object.
 */
public final class CdrSummaryBuilder {
    private CdrSummaryBuilder() {}

    public static AbstractCdrSummary Build(cdr cdr, acc_chargeable customerChargeable, SummaryBucket bucket) {
        AbstractCdrSummary summary = CreateInstance(customerChargeable.servicegroup, bucket);
        PopulateCommon(summary, cdr);
        summary.tup_starttime = bucket == SummaryBucket.Hour
                ? LocalDateTime.of(cdr.StartTime.getYear(), cdr.StartTime.getMonthValue(),
                        cdr.StartTime.getDayOfMonth(), cdr.StartTime.getHour(), 0, 0)
                : cdr.StartTime.toLocalDate().atStartOfDay();
        PopulateServiceGroup(summary, cdr, customerChargeable);
        ReplaceNullsWithDefault(summary);
        return summary;
    }

    private static AbstractCdrSummary CreateInstance(int serviceGroup, SummaryBucket bucket) {
        if (serviceGroup == 10 && bucket == SummaryBucket.Day) return new sum_voice_day_03();
        if (serviceGroup == 10 && bucket == SummaryBucket.Hour) return new sum_voice_hr_03();
        if (serviceGroup == 11 && bucket == SummaryBucket.Day) return new sum_voice_day_02();
        if (serviceGroup == 11 && bucket == SummaryBucket.Hour) return new sum_voice_hr_02();
        // SG15 international-outgoing → sum_voice_*_05 (verified against production telcobright.sum_voice_day_05 /
        // sum_voice_hr_05; the stale legacy source said _02, the live data is _05).
        if (serviceGroup == 15 && bucket == SummaryBucket.Day) return new sum_voice_day_05();
        if (serviceGroup == 15 && bucket == SummaryBucket.Hour) return new sum_voice_hr_05();
        throw new UnsupportedOperationException("No summary table mapped for service group " + serviceGroup + ".");
    }

    // legacy CdrSummaryFactory.CreateInstanceWithoutDate (the SG-independent identity + counts + durations).
    private static void PopulateCommon(AbstractCdrSummary s, cdr cdr) {
        s.tup_switchid = cdr.SwitchId;
        s.tup_inpartnerid = cdr.InPartnerId != null ? cdr.InPartnerId : 0;
        s.tup_outpartnerid = cdr.OutPartnerId != null ? cdr.OutPartnerId : 0;
        s.tup_incomingroute = cdr.IncomingRoute != null ? cdr.IncomingRoute : "";
        s.tup_outgoingroute = cdr.OutgoingRoute != null ? cdr.OutgoingRoute : "";
        s.tup_incomingip = cdr.OriginatingIP != null ? cdr.OriginatingIP : "";
        s.tup_outgoingip = cdr.TerminatingIP != null ? cdr.TerminatingIP : "";

        s.totalcalls = 1;
        s.connectedcalls = cdr.ConnectTime != null ? 1 : 0;
        // connectedcallsCC ("connected flag"): legacy CdrSummaryFactory.CreateInstanceWithoutDate
        // (TelcobrightVS13) uses NERSuccess == 1 — keep that exact semantic.
        s.connectedcallsCC = (cdr.NERSuccess != null && cdr.NERSuccess == 1) ? 1 : 0;
        s.successfulcalls = cdr.ChargingStatus != null ? cdr.ChargingStatus : 0;
        s.actualduration = cdr.DurationSec;
        s.roundedduration = cdr.RoundedDuration != null ? cdr.RoundedDuration : BigDecimal.ZERO;
        s.duration1 = cdr.Duration1 != null ? cdr.Duration1 : BigDecimal.ZERO;
        s.duration2 = cdr.Duration2 != null ? cdr.Duration2 : BigDecimal.ZERO;
        s.duration3 = cdr.Duration3 != null ? cdr.Duration3 : BigDecimal.ZERO;
        s.PDD = new BigDecimal(Float.toString(cdr.PDD != null ? cdr.PDD : 0f));
    }

    private static void PopulateServiceGroup(AbstractCdrSummary s, cdr cdr, acc_chargeable chargeable) {
        s.tup_countryorareacode = cdr.CountryCode;

        if (chargeable.servicegroup == 10) {   // SgDomOffnetOut.SetServiceGroupWiseSummaryParams (customer leg)
            s.tup_destinationId = cdr.AnsIdTerm != null ? cdr.AnsIdTerm.toString() : null;
            s.tup_matchedprefixsupplier = cdr.MatchedPrefixSupplier;
            // SgIntlTransitVoice.SetChargingSummaryInCustomerDirection:
            s.tup_matchedprefixcustomer = chargeable.Prefix;
            s.tup_customerrate = chargeable.unitPriceOrCharge;
            s.tup_customercurrency = chargeable.idBilledUom;
            s.customercost = chargeable.BilledAmount;
            s.tup_tax1currency = "BDT";
            s.tax1 = chargeable.TaxAmount1 != null ? chargeable.TaxAmount1 : BigDecimal.ZERO;
            // supplier leg (admin FULL) — populated on the cdr by SfA2Z's supplier direction:
            s.suppliercost = cdr.OutPartnerCost != null ? cdr.OutPartnerCost : BigDecimal.ZERO;
            s.tup_supplierrate = cdr.SupplierRate != null ? cdr.SupplierRate : BigDecimal.ZERO;
            s.tup_suppliercurrency = "BDT";
            s.tup_tax2currency = "BDT";
            s.tax2 = cdr.Tax2 != null ? cdr.Tax2 : BigDecimal.ZERO;
            // vat (cdr.ZAmount) / longDecimalAmount1 (cdr.CostAnsIn, anscost) need the ANS extended leg — deferred.
        } else if (chargeable.servicegroup == 11) {   // SgDomOffnetIn.SetServiceGroupWiseSummaryParams (customer leg)
            s.tup_sourceId = cdr.AnsIdOrig != null ? cdr.AnsIdOrig.toString() : null;
            s.tup_matchedprefixsupplier = cdr.MatchedPrefixSupplier;
            // SgIntlTransitVoice.SetChargingSummaryInCustomerDirection — the customer-leg fields come off the
            // customer acc_chargeable, exactly as SG10. The prior MatchedPrefixY / OtherDecAmount1 / OtherAmount1
            // reads were DEAD: the rater never writes those, so they always folded 0/null.
            s.tup_matchedprefixcustomer = chargeable.Prefix;
            s.tup_customerrate = chargeable.unitPriceOrCharge;
            s.tup_customercurrency = chargeable.idBilledUom;
            s.customercost = chargeable.BilledAmount;
            s.tup_tax1currency = "BDT";
            s.tax1 = chargeable.TaxAmount1 != null ? chargeable.TaxAmount1 : BigDecimal.ZERO;
        } else if (chargeable.servicegroup == 15) {   // SgIntlOutIptsp.SetServiceGroupWiseSummaryParams (Xyz customer leg)
            // legacy field mapping: invoice -> customercost; x/y/z amounts -> longDecimalAmount1/2/3; the
            // x-rate / y-rate(USD) / usd-rate ride on OtherDecAmount1/2/3; btrc -> tax1. SG15 has no separate
            // supplier chargeable (supplier cost is the embedded y component), so no supplier-leg reads here.
            s.tup_sourceId = cdr.AnsIdOrig != null ? cdr.AnsIdOrig.toString() : null;
            s.tup_matchedprefixcustomer = cdr.MatchedPrefixY;
            s.customercost = chargeable.BilledAmount != null ? chargeable.BilledAmount : BigDecimal.ZERO;
            s.tup_customerrate = chargeable.OtherDecAmount1 != null ? chargeable.OtherDecAmount1 : BigDecimal.ZERO;   // x rate
            s.tup_supplierrate = chargeable.OtherDecAmount2 != null ? chargeable.OtherDecAmount2 : BigDecimal.ZERO;   // y rate (USD)
            s.tup_customercurrency = chargeable.OtherDecAmount3 != null ? chargeable.OtherDecAmount3.toString() : null; // usd rate
            s.tup_suppliercurrency = "USD";
            s.longDecimalAmount1 = chargeable.OtherAmount1 != null ? chargeable.OtherAmount1 : BigDecimal.ZERO;       // x amount (BDT)
            s.longDecimalAmount2 = chargeable.OtherAmount2 != null ? chargeable.OtherAmount2 : BigDecimal.ZERO;       // y amount (USD)
            s.longDecimalAmount3 = chargeable.OtherAmount3 != null ? chargeable.OtherAmount3 : BigDecimal.ZERO;       // z amount (BDT)
            s.tup_tax1currency = "BDT";
            s.tax1 = chargeable.TaxAmount1 != null ? chargeable.TaxAmount1 : BigDecimal.ZERO;                        // btrc rev-share
        }
    }

    private static void ReplaceNullsWithDefault(AbstractCdrSummary s) {
        if (s.tup_countryorareacode == null) s.tup_countryorareacode = "";
        if (s.tup_matchedprefixcustomer == null) s.tup_matchedprefixcustomer = "";
        if (s.tup_matchedprefixsupplier == null) s.tup_matchedprefixsupplier = "";
        if (s.tup_sourceId == null) s.tup_sourceId = "";
        if (s.tup_destinationId == null) s.tup_destinationId = "";
        if (s.tup_customercurrency == null) s.tup_customercurrency = "";
        if (s.tup_suppliercurrency == null) s.tup_suppliercurrency = "";
        if (s.tup_tax1currency == null) s.tup_tax1currency = "";
        if (s.tup_tax2currency == null) s.tup_tax2currency = "";
        if (s.tup_vatcurrency == null) s.tup_vatcurrency = "";
    }
}
