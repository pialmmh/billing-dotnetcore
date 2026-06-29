package com.telcobright.billing.testsupport;

import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builders for the verbatim legacy rating entities (rateassign + rateplanassignmenttuple) so the rating
 * tests stay terse. Port of the C# {@code TestData}; C#'s named/optional args become a fluent builder
 * (Java has neither). Only the fields the rater/matcher read are set; the rest default.
 *
 * <pre>
 *   C#:   TestData.Ra(1712, 1.0m, idRatePlan: 7)
 *   Java: TestData.Ra(1712, "1.0").idRatePlan(7).build()
 *
 *   C#:   TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7))
 *   Java: TestData.Tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7))
 * </pre>
 *
 * Note: when a test needs a bare {@code rateassign} (not inside a Tup), call {@code .build()} on the Ra.
 * {@link #Tup} accepts the Ra builders directly and builds them.
 */
public final class TestData {
    private TestData() {}

    // Distinct id per tuple — real tuples have unique ids, and the RateCache keys its per-day rate dicts by
    // TupleByPeriod{IdAssignmentTuple}, so tuples sharing an id would collide. Value is irrelevant to asserts.
    private static int _nextTupleId = 1;

    public static Ra Ra(int prefix, BigDecimal amount) { return new Ra(prefix, amount); }

    /** Convenience: amount as a decimal string (keeps BigDecimal scale exact, like the C# {@code 1.0m}). */
    public static Ra Ra(int prefix, String amount) { return new Ra(prefix, new BigDecimal(amount)); }

    /** Fluent builder for a rateassign. Defaults mirror the C# optional-arg defaults. */
    public static final class Ra {
        private final rateassign r = new rateassign();

        Ra(int prefix, BigDecimal amount) {
            r.Prefix = prefix;
            r.rateamount = amount;
            r.Resolution = 0;
            r.SurchargeTime = 0;
            r.MinDurationSec = 0f;
            r.idrateplan = 7L;
            r.Category = (byte) 1;
            r.SubCategory = (byte) 1;
            r.startdate = LocalDateTime.of(1, 1, 1, 0, 0);   // C# DateTime.MinValue
            r.enddate = null;
            r.Inactive = 0;
            r.OtherAmount1 = 0f;   // SF11 IOF / additional charge
            r.OtherAmount3 = 0f;   // SF10 VAT fraction / SF11 BTRC fraction
        }

        public Ra resolution(int v) { r.Resolution = v; return this; }
        public Ra surchargeTime(int v) { r.SurchargeTime = v; return this; }
        public Ra minDurationSec(float v) { r.MinDurationSec = v; return this; }
        public Ra idRatePlan(long v) { r.idrateplan = v; return this; }
        public Ra category(int v) { r.Category = (byte) v; return this; }
        public Ra subCategory(int v) { r.SubCategory = (byte) v; return this; }
        public Ra startdate(LocalDateTime v) { r.startdate = v; return this; }
        public Ra enddate(LocalDateTime v) { r.enddate = v; return this; }
        public Ra inactive(int v) { r.Inactive = v; return this; }
        public Ra otherAmount1(float v) { r.OtherAmount1 = v; return this; }
        public Ra otherAmount3(float v) { r.OtherAmount3 = v; return this; }

        public rateassign build() { return r; }
    }

    public static rateplanassignmenttuple Tup(int idService, int assignDirection, Integer idPartner,
            Integer route, int priority, Ra... rates) {
        rateplanassignmenttuple t = new rateplanassignmenttuple();
        t.id = _nextTupleId++;
        t.idService = idService;
        t.AssignDirection = assignDirection;
        t.idpartner = idPartner;
        t.route = route;
        t.priority = priority;
        List<rateassign> list = new ArrayList<>();
        for (Ra ra : rates) list.add(ra.build());
        t.rateassigns = list;
        return t;
    }
}
