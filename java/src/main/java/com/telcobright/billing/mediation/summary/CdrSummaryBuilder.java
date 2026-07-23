package com.telcobright.billing.mediation.summary;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_03;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_03;

/**
 * Builds a per-call AbstractCdrSummary from the cdr + its customer acc_chargeable — the port of legacy
 * {@code CdrSummaryFactory.CreateInstanceWithoutDate} (the common identity/count/duration fields) + the
 * day/hour bucketing + each SG's {@code SetServiceGroupWiseSummaryParams}:
 * <ul>
 * <li>SG10 -&gt; sum_voice_day_03/hr_03 (SfA2ZWithVatTax customer leg).</li>
 * <li>SG11 -&gt; sum_voice_day_02/hr_02 (SfDomOffNetInAns customer leg).</li>
 * </ul>
 * CUSTOMER leg only — the supplier/extended-leg summary fields (suppliercost, tax2, vat, anscost) need
 * those legs and stay 0. Aggregation by {@code GetTupleKey()} + the single-connection write are the
 * persistence slice (deferred); this just builds the summary object.
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
            s.tup_matchedprefixcustomer = cdr.MatchedPrefixY;
            s.tup_sourceId = cdr.AnsIdOrig != null ? cdr.AnsIdOrig.toString() : null;
            s.customercost = chargeable.BilledAmount;
            s.tup_customerrate = chargeable.OtherDecAmount1 != null ? chargeable.OtherDecAmount1 : BigDecimal.ZERO;     // legacy reads chargeable.OtherDecAmount1 (x rate)
            s.longDecimalAmount1 = chargeable.OtherAmount1 != null ? chargeable.OtherAmount1 : BigDecimal.ZERO;       // x amount
            s.tax1 = chargeable.TaxAmount1 != null ? chargeable.TaxAmount1 : BigDecimal.ZERO;
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
