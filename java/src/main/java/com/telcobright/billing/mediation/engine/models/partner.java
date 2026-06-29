// Ported from legacy Models_Mediation/partner.cs (MediationModel.partner).
// SCALAR fields kept verbatim; EF navigation properties (accounts/bridgedroutes/ratetaskassigns/
// routes) and the collection-initialising ctor REMOVED — they are EF lazy-loading tech that pull the
// whole entity graph and are not used by the mediation logic (SG 10/11 reads only PartnerType).
package com.telcobright.billing.mediation.engine.models;

import java.time.LocalDateTime;

public class partner {
    public int idPartner;
    public String PartnerName;
    public String AlternateNameInvoice;
    public String AlternateNameOther;
    public String Address1;
    public String Address2;
    public String City;
    public String State;
    public String PostalCode;
    public String Country;
    public String Telephone;
    public String email;
    public int CustomerPrePaid;
    public int PartnerType;
    public Integer billingdate;
    public Integer AllowedDaysForInvoicePayment;
    public Integer timezone;
    public LocalDateTime date1;
    public Integer field1;
    public Integer field2;
    public Integer field3;
    public String field4;
    public String field5;
    public Float refasr;
    public Float refacd;
    public Float refccr;
    public Float refccrbycc;
    public Float refpdd;
    public Float refasrfas;
    public int DefaultCurrency;
    public String invoiceAddress;
    public String vatRegistrationNo;
    public String paymentAdvice;
}
