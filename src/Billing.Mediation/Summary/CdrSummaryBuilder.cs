using MediationModel;

namespace Billing.Mediation.Summary;

/// <summary>Which time bucket a summary rolls into (the legacy Daily/Hourly factories).</summary>
public enum SummaryBucket { Day, Hour }

/// <summary>
/// Builds a per-call <see cref="AbstractCdrSummary"/> from the cdr + its customer <see cref="acc_chargeable"/>
/// — the port of legacy <c>CdrSummaryFactory.CreateInstanceWithoutDate</c> (the common identity/count/
/// duration fields) + the day/hour bucketing + each SG's <c>SetServiceGroupWiseSummaryParams</c>:
/// <list type="bullet">
/// <item>SG10 → <c>sum_voice_day_03</c>/<c>hr_03</c> (SfA2ZWithVatTax customer leg).</item>
/// <item>SG11 → <c>sum_voice_day_02</c>/<c>hr_02</c> (SfDomOffNetInAns customer leg).</item>
/// </list>
/// CUSTOMER leg only — the supplier/extended-leg summary fields (suppliercost, tax2, vat, anscost) need
/// those legs and stay 0. Aggregation by <c>GetTupleKey()</c> + the single-connection write are the
/// persistence slice (deferred); this just builds the summary object.
/// </summary>
public static class CdrSummaryBuilder
{
    public static AbstractCdrSummary Build(cdr cdr, acc_chargeable customerChargeable, SummaryBucket bucket)
    {
        var summary = CreateInstance(customerChargeable.servicegroup, bucket);
        PopulateCommon(summary, cdr);
        summary.tup_starttime = bucket == SummaryBucket.Hour
            ? new DateTime(cdr.StartTime.Year, cdr.StartTime.Month, cdr.StartTime.Day, cdr.StartTime.Hour, 0, 0)
            : cdr.StartTime.Date;
        PopulateServiceGroup(summary, cdr, customerChargeable);
        ReplaceNullsWithDefault(summary);
        return summary;
    }

    private static AbstractCdrSummary CreateInstance(int serviceGroup, SummaryBucket bucket) => (serviceGroup, bucket) switch
    {
        (10, SummaryBucket.Day) => new sum_voice_day_03(),
        (10, SummaryBucket.Hour) => new sum_voice_hr_03(),
        (11, SummaryBucket.Day) => new sum_voice_day_02(),
        (11, SummaryBucket.Hour) => new sum_voice_hr_02(),
        _ => throw new NotSupportedException($"No summary table mapped for service group {serviceGroup}."),
    };

    // legacy CdrSummaryFactory.CreateInstanceWithoutDate (the SG-independent identity + counts + durations).
    private static void PopulateCommon(AbstractCdrSummary s, cdr cdr)
    {
        s.tup_switchid = cdr.SwitchId;
        s.tup_inpartnerid = cdr.InPartnerId ?? 0;
        s.tup_outpartnerid = cdr.OutPartnerId ?? 0;
        s.tup_incomingroute = cdr.IncomingRoute ?? "";
        s.tup_outgoingroute = cdr.OutgoingRoute ?? "";
        s.tup_incomingip = cdr.OriginatingIP ?? "";
        s.tup_outgoingip = cdr.TerminatingIP ?? "";

        s.totalcalls = 1;
        s.connectedcalls = cdr.ConnectTime != null ? 1 : 0;
        s.connectedcallsCC = cdr.NERSuccess == 1 ? 1 : 0;
        s.successfulcalls = cdr.ChargingStatus ?? 0;
        s.actualduration = cdr.DurationSec;
        s.roundedduration = cdr.RoundedDuration ?? 0;
        s.duration1 = cdr.Duration1 ?? 0;
        s.duration2 = cdr.Duration2 ?? 0;
        s.duration3 = cdr.Duration3 ?? 0;
        s.PDD = (decimal)(cdr.PDD ?? 0f);
    }

    private static void PopulateServiceGroup(AbstractCdrSummary s, cdr cdr, acc_chargeable chargeable)
    {
        s.tup_countryorareacode = cdr.CountryCode;

        if (chargeable.servicegroup == 10)   // SgDomOffnetOut.SetServiceGroupWiseSummaryParams (customer leg)
        {
            s.tup_destinationId = cdr.AnsIdTerm?.ToString();
            s.tup_matchedprefixsupplier = cdr.MatchedPrefixSupplier;
            // SgIntlTransitVoice.SetChargingSummaryInCustomerDirection:
            s.tup_matchedprefixcustomer = chargeable.Prefix;
            s.tup_customerrate = chargeable.unitPriceOrCharge;
            s.tup_customercurrency = chargeable.idBilledUom;
            s.customercost = chargeable.BilledAmount;
            s.tup_tax1currency = "BDT";
            s.tax1 = chargeable.TaxAmount1 ?? 0;
            // supplier leg (admin FULL) — populated on the cdr by SfA2Z's supplier direction:
            s.suppliercost = cdr.OutPartnerCost ?? 0;
            s.tup_supplierrate = cdr.SupplierRate ?? 0;
            s.tup_suppliercurrency = "BDT";
            s.tup_tax2currency = "BDT";
            s.tax2 = cdr.Tax2 ?? 0;
            // vat (cdr.ZAmount) / longDecimalAmount1 (cdr.CostAnsIn, anscost) need the ANS extended leg — deferred.
        }
        else if (chargeable.servicegroup == 11)   // SgDomOffnetIn.SetServiceGroupWiseSummaryParams (customer leg)
        {
            s.tup_matchedprefixcustomer = cdr.MatchedPrefixY;
            s.tup_sourceId = cdr.AnsIdOrig?.ToString();
            s.customercost = chargeable.BilledAmount;
            s.tup_customerrate = chargeable.OtherDecAmount1 ?? 0;     // legacy reads chargeable.OtherDecAmount1 (x rate)
            s.longDecimalAmount1 = chargeable.OtherAmount1 ?? 0;       // x amount
            s.tax1 = chargeable.TaxAmount1 ?? 0;
        }
    }

    private static void ReplaceNullsWithDefault(AbstractCdrSummary s)
    {
        s.tup_countryorareacode ??= "";
        s.tup_matchedprefixcustomer ??= "";
        s.tup_matchedprefixsupplier ??= "";
        s.tup_sourceId ??= "";
        s.tup_destinationId ??= "";
        s.tup_customercurrency ??= "";
        s.tup_suppliercurrency ??= "";
        s.tup_tax1currency ??= "";
        s.tup_tax2currency ??= "";
        s.tup_vatcurrency ??= "";
    }
}
