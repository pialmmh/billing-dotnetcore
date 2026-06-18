// Ported VERBATIM from legacy Models_Mediation/acc_chargeable.cs (MediationModel.acc_chargeable).
// Plain POCO — the chargeable a service family produces. Keyed in CdrExt.Chargeables by (sg, sf, dir).
#nullable disable

namespace MediationModel
{
    using System;
    using System.Collections.Generic;

    public partial class acc_chargeable
    {
        public long id { get; set; }
        public string uniqueBillId { get; set; }
        public long idEvent { get; set; }
        public System.DateTime transactionTime { get; set; }
        public Nullable<sbyte> assignedDirection { get; set; }
        public string description { get; set; }
        public long glAccountId { get; set; }
        public int servicegroup { get; set; }
        public int servicefamily { get; set; }
        public long ProductId { get; set; }
        public string idBilledUom { get; set; }
        public string idQuantityUom { get; set; }
        public decimal BilledAmount { get; set; }
        public decimal Quantity { get; set; }
        public decimal unitPriceOrCharge { get; set; }
        public string Prefix { get; set; }
        public long RateId { get; set; }
        public Nullable<decimal> TaxAmount1 { get; set; }
        public Nullable<decimal> TaxAmount2 { get; set; }
        public Nullable<decimal> TaxAmount3 { get; set; }
        public Nullable<decimal> VatAmount1 { get; set; }
        public Nullable<decimal> VatAmount2 { get; set; }
        public Nullable<decimal> VatAmount3 { get; set; }
        public Nullable<decimal> OtherAmount1 { get; set; }
        public Nullable<decimal> OtherAmount2 { get; set; }
        public Nullable<decimal> OtherAmount3 { get; set; }
        public Nullable<decimal> OtherDecAmount1 { get; set; }
        public Nullable<decimal> OtherDecAmount2 { get; set; }
        public Nullable<decimal> OtherDecAmount3 { get; set; }
        public Nullable<long> createdByJob { get; set; }
        public Nullable<long> changedByJob { get; set; }
        public int idBillingrule { get; set; }
        public string jsonDetail { get; set; }
    }
}
