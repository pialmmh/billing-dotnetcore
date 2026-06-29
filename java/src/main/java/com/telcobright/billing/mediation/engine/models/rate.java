// Ported VERBATIM from legacy Models_Mediation/rate.cs (MediationModel.rate) — the MATCHED rate entity.
// SCALAR fields kept verbatim (names + nullability). `Prefix` is a String (the dial prefix). This POCO
// has NO EF navigation properties, so nothing was removed. config-manager serves this legacy shape so
// .NET/Java deserialize 1:1. (decimal->BigDecimal; float->float; Nullable<float>->Float;
// Nullable<int>->Integer; Nullable<long>->Long; Nullable<sbyte>->Byte; DateTime/DateTime?->LocalDateTime.)
package com.telcobright.billing.mediation.engine.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class rate {
    public long id;
    public int ProductId;
    public String Prefix;
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
    public Integer idrateplan;
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
    public BigDecimal OtherAmount1;
    public BigDecimal OtherAmount2;
    public BigDecimal OtherAmount3;
    public BigDecimal OtherAmount4;
    public BigDecimal OtherAmount5;
    public BigDecimal OtherAmount6;
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
    public Integer billingspan;
    public Integer RateAmountRoundupDecimal;
}
