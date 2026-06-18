using MediationModel;

namespace Billing.Tests;

/// <summary>Builders for the verbatim legacy rating entities (rateassign + rateplanassignmenttuple) so
/// the rating tests stay terse. Only the fields the rater/matcher read are set; the rest default.</summary>
internal static class TestData
{
    public static rateassign Ra(int prefix, decimal amount, int resolution = 0, int surchargeTime = 0,
        float minDurationSec = 0, long idRatePlan = 7, sbyte category = 1, sbyte subCategory = 1,
        DateTime? startdate = null, DateTime? enddate = null, int inactive = 0,
        float otherAmount1 = 0, float otherAmount3 = 0) => new()
    {
        Prefix = prefix,
        rateamount = amount,
        Resolution = resolution,
        SurchargeTime = surchargeTime,
        MinDurationSec = minDurationSec,
        idrateplan = idRatePlan,
        Category = category,
        SubCategory = subCategory,
        startdate = startdate ?? DateTime.MinValue,
        enddate = enddate,
        Inactive = inactive,
        OtherAmount1 = otherAmount1,   // SF11 IOF / additional charge
        OtherAmount3 = otherAmount3,   // SF10 VAT fraction / SF11 BTRC fraction
    };

    public static rateplanassignmenttuple Tup(int idService, int assignDirection, int? idPartner, int? route,
        int priority, params rateassign[] rates) => new()
    {
        idService = idService,
        AssignDirection = assignDirection,
        idpartner = idPartner,
        route = route,
        priority = priority,
        rateassigns = rates.ToList(),
    };
}
