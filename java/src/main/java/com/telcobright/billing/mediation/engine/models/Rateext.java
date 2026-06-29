// Ported VERBATIM from legacy Models_Mediation/EntityExtensions/Rateext.cs (MediationModel.Rateext).
// `Rateext : rate` -> `Rateext extends rate`; adds the rate-plan-assignment overlay fields and the
// computed period accessors. The C# auto-properties P_Startdate / P_Enddate are exposed here as METHODS
// P_Startdate() / P_Enddate() with the EXACT legacy getter logic preserved. Field/member order kept as
// in the legacy file (IdRatePlanAssignmentTuple, ToString, then the rest).
package com.telcobright.billing.mediation.engine.models;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class Rateext extends rate {
    public int IdRatePlanAssignmentTuple;

    @Override
    public String toString() {
        return new StringBuilder().append(this.Prefix).append("/")
                .append(this.id)
                .append("/")
                .append(this.P_Startdate() != null ? this.P_Startdate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "null")
                .append("/")
                .append(this.P_Enddate() != null ? this.P_Enddate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "null").toString();
    }

    public Integer Priority;
    public int AssignmentFlag;
    public LocalDateTime Enddatebyrateplan;
    public LocalDateTime Startdatebyrateplan;

    public int OpenRateAssignment;

    public int IdPartner;
    public int IdRoute;
    public String TechPrefix;
    public String Pcurrency;

    // NOTE: C# `Nullable<int>.ToString()` yields "" when null (NOT "null"); preserved verbatim below.
    public String PrefixWithTechPrefix(Map<String, rateplan> dicRatePlan) {
        return dicRatePlan.get(this.idrateplan != null ? this.idrateplan.toString() : "").field4 + this.Prefix;
    }

    public LocalDateTime P_Startdate() {
        if (this.AssignmentFlag == 0)
            return this.startdate;
        // C# lifted `>=` is false when Startdatebyrateplan is null, so the ternary returns that null; otherwise max(startdate, Startdatebyrateplan).
        return (this.Startdatebyrateplan != null && this.startdate.compareTo(this.Startdatebyrateplan) >= 0)
                ? this.startdate : this.Startdatebyrateplan;
    }

    public LocalDateTime P_Enddate() {
        if (this.AssignmentFlag == 0)
            return this.enddate;

        if (this.OpenRateAssignment == 1) {//the rate's rateplan assignment is open
            if (this.enddate == null)
                return null;
            else//enddate not null
                return this.enddate;
        } else {//rateplan assignment has an enddate, NOT OPEN
            if (this.enddate == null)
                return this.Enddatebyrateplan;
            else//enddate not null
                // C# lifted `<=` is false when Enddatebyrateplan is null, so the ternary returns that null; otherwise min(enddate, Enddatebyrateplan).
                return (this.Enddatebyrateplan != null && this.enddate.compareTo(this.Enddatebyrateplan) <= 0)
                        ? this.enddate : this.Enddatebyrateplan;
        }
    }
}
