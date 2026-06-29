// Ported VERBATIM from legacy Models_Mediation/rateplan.cs (MediationModel.rateplan).
// SCALAR fields kept verbatim; EF navigation properties (enumbillingspan, rateassigns,
// ratetaskreferences, timezone1, ratetaskassigns) and the collection-initialising ctor REMOVED — they
// are EF lazy-loading tech not used by the mediation/rating logic (the rater reads field4=techPrefix,
// BillingSpan (String uom), RateAmountRoundupDecimal, Resolution, mindurationsec, SurchargeTime,
// SurchargeAmount, Category/SubCategory, RatePlanName, Currency).
package com.telcobright.billing.mediation.engine.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class rateplan {
    public int id;
    public int Type;
    public String RatePlanName;
    public String Description;
    public LocalDateTime date1;
    public Integer field1;
    public Integer field2;
    public Integer field3;
    public String field4;
    public String field5;
    public int TimeZone;
    public int idCarrier;
    public String Currency;
    public LocalDateTime codedeletedate;
    public Integer ChangeCommitted;
    public int Resolution;
    public float mindurationsec;
    public int SurchargeTime;
    public BigDecimal SurchargeAmount;
    public Byte Category;
    public Byte SubCategory;
    public String BillingSpan;
    public Integer RateAmountRoundupDecimal;
}
