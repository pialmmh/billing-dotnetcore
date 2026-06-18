// Ported from legacy Models_Mediation/partner.cs (MediationModel.partner).
// SCALAR fields kept verbatim; EF navigation properties (accounts/bridgedroutes/ratetaskassigns/
// routes) and the collection-initialising ctor REMOVED — they are EF lazy-loading tech that pull the
// whole entity graph and are not used by the mediation logic (SG 10/11 reads only PartnerType).
#nullable disable

namespace MediationModel
{
    using System;
    using System.Collections.Generic;

    public partial class partner
    {
        public int idPartner { get; set; }
        public string PartnerName { get; set; }
        public string AlternateNameInvoice { get; set; }
        public string AlternateNameOther { get; set; }
        public string Address1 { get; set; }
        public string Address2 { get; set; }
        public string City { get; set; }
        public string State { get; set; }
        public string PostalCode { get; set; }
        public string Country { get; set; }
        public string Telephone { get; set; }
        public string email { get; set; }
        public int CustomerPrePaid { get; set; }
        public int PartnerType { get; set; }
        public Nullable<int> billingdate { get; set; }
        public Nullable<int> AllowedDaysForInvoicePayment { get; set; }
        public Nullable<int> timezone { get; set; }
        public Nullable<System.DateTime> date1 { get; set; }
        public Nullable<int> field1 { get; set; }
        public Nullable<int> field2 { get; set; }
        public Nullable<int> field3 { get; set; }
        public string field4 { get; set; }
        public string field5 { get; set; }
        public Nullable<float> refasr { get; set; }
        public Nullable<float> refacd { get; set; }
        public Nullable<float> refccr { get; set; }
        public Nullable<float> refccrbycc { get; set; }
        public Nullable<float> refpdd { get; set; }
        public Nullable<float> refasrfas { get; set; }
        public int DefaultCurrency { get; set; }
        public string invoiceAddress { get; set; }
        public string vatRegistrationNo { get; set; }
        public string paymentAdvice { get; set; }
    }
}
