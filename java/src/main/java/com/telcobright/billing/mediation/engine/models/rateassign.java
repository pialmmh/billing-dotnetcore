// Ported VERBATIM from legacy Models_Mediation/rateassign.cs (MediationModel.rateassign).
// Plain POCO — EF navigation properties (rateplan, rateplanassignmenttuple back-ref) REMOVED so it
// compiles on .NET 8 and deserializes 1:1 from config-manager's legacy-shaped JSON. All scalar fields
// kept verbatim (the rater reads Prefix/Category/SubCategory/startdate/enddate + rateamount/Resolution/
// MinDurationSec/SurchargeTime; the rest ride along for fidelity).
package com.telcobright.billing.mediation.engine.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class rateassign {
    public int id;
    public int Prefix;
    public String description;
    public BigDecimal rateamount;
    public int WeekDayStart;
    public int WeekDayEnd;
    public String starttime;
    public String endtime;
    public int Resolution;
    public float MinDurationSec;
    public int SurchargeTime;
    public BigDecimal SurchargeAmount;
    public Long idrateplan;
    public String CountryCode;
    public LocalDateTime date1;
    public Integer field1;
    public Integer field2;
    public int field3;
    public String field4;
    public String field5;
    public LocalDateTime startdate;
    public LocalDateTime enddate;
    public int Inactive;
    public int RouteDisabled;
    public int Type;
    public int Currency;
    public Float OtherAmount1;
    public Float OtherAmount2;
    public Float OtherAmount3;
    public BigDecimal OtherAmount4;
    public BigDecimal OtherAmount5;
    public Float OtherAmount6;
    public Float OtherAmount7;
    public Float OtherAmount8;
    public Float OtherAmount9;
    public Float OtherAmount10;
    public BigDecimal TimeZoneOffsetSec;
    public Integer RatePosition;
    public Float IgwPercentageIn;
    public String ConflictingRateIds;
    public Long ChangedByTaskId;
    public LocalDateTime ChangedOn;
    public Integer Status;
    public Long idPreviousRate;
    public Byte EndPreviousRate;
    public Byte Category;
    public Byte SubCategory;
    public Integer ChangeCommitted;
    public String ConflictingRates;
    public String OverlappingRates;
    public String Comment1;
    public String Comment2;
    public String BillingParams;
    // The legacy EF nav rateplanassignmenttuple's underlying FK column — the tuple this rate belongs
    // to (used by the RateCache loader to group rates per tuple).
    public Integer idrateplanassignmenttuple;
}
