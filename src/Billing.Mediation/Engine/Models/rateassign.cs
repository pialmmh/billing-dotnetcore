// Ported VERBATIM from legacy Models_Mediation/rateassign.cs (MediationModel.rateassign).
// Plain POCO — EF navigation properties (rateplan, rateplanassignmenttuple back-ref) REMOVED so it
// compiles on .NET 8 and deserializes 1:1 from config-manager's legacy-shaped JSON. All scalar fields
// kept verbatim (the rater reads Prefix/Category/SubCategory/startdate/enddate + rateamount/Resolution/
// MinDurationSec/SurchargeTime; the rest ride along for fidelity).
#nullable disable

namespace MediationModel
{
    using System;

    public partial class rateassign
    {
        public int id { get; set; }
        public int Prefix { get; set; }
        public string description { get; set; }
        public decimal rateamount { get; set; }
        public int WeekDayStart { get; set; }
        public int WeekDayEnd { get; set; }
        public string starttime { get; set; }
        public string endtime { get; set; }
        public int Resolution { get; set; }
        public float MinDurationSec { get; set; }
        public int SurchargeTime { get; set; }
        public decimal SurchargeAmount { get; set; }
        public Nullable<long> idrateplan { get; set; }
        public string CountryCode { get; set; }
        public Nullable<System.DateTime> date1 { get; set; }
        public Nullable<int> field1 { get; set; }
        public Nullable<int> field2 { get; set; }
        public int field3 { get; set; }
        public string field4 { get; set; }
        public string field5 { get; set; }
        public System.DateTime startdate { get; set; }
        public Nullable<System.DateTime> enddate { get; set; }
        public int Inactive { get; set; }
        public int RouteDisabled { get; set; }
        public int Type { get; set; }
        public int Currency { get; set; }
        public Nullable<float> OtherAmount1 { get; set; }
        public Nullable<float> OtherAmount2 { get; set; }
        public Nullable<float> OtherAmount3 { get; set; }
        public Nullable<decimal> OtherAmount4 { get; set; }
        public Nullable<decimal> OtherAmount5 { get; set; }
        public Nullable<float> OtherAmount6 { get; set; }
        public Nullable<float> OtherAmount7 { get; set; }
        public Nullable<float> OtherAmount8 { get; set; }
        public Nullable<float> OtherAmount9 { get; set; }
        public Nullable<float> OtherAmount10 { get; set; }
        public decimal TimeZoneOffsetSec { get; set; }
        public Nullable<int> RatePosition { get; set; }
        public Nullable<float> IgwPercentageIn { get; set; }
        public string ConflictingRateIds { get; set; }
        public Nullable<long> ChangedByTaskId { get; set; }
        public Nullable<System.DateTime> ChangedOn { get; set; }
        public Nullable<int> Status { get; set; }
        public Nullable<long> idPreviousRate { get; set; }
        public Nullable<sbyte> EndPreviousRate { get; set; }
        public Nullable<sbyte> Category { get; set; }
        public Nullable<sbyte> SubCategory { get; set; }
        public Nullable<int> ChangeCommitted { get; set; }
        public string ConflictingRates { get; set; }
        public string OverlappingRates { get; set; }
        public string Comment1 { get; set; }
        public string Comment2 { get; set; }
        public string BillingParams { get; set; }
    }
}
