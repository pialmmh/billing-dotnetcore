package com.telcobright.billing.testsupport;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceCategory;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.context.ServiceGroupRule;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.mediation.validation.IValidationRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builders for the verbatim legacy rating entities so the rating tests stay terse, in the NEW (Rateext) model:
 * {@link #Ra} builds a {@code rate} ROW (the actual dial-prefix rate); a {@link Fixture} (via {@link #fixture})
 * assembles a tenant's rate-plan-assignment tuples (each with its rateassign JOIN rows), the rate rows per rate
 * plan, the rate plans and the billing-span table, exactly as the config-fed RateCache loader joins them. The
 * resolver-only tests use {@link #tuple}; the ConfigManagerMapper test uses {@link #joinTuple}.
 *
 * <pre>
 *   var f = TestData.fixture();
 *   f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7));
 *   MediationContext med = f.mediation();          // resolver + per-day RateCache, both from the SAME data
 * </pre>
 */
public final class TestData {
    private TestData() {}

    static final LocalDateTime MIN = LocalDateTime.of(1, 1, 1, 0, 0);   // C# DateTime.MinValue

    // Distinct id per tuple — real tuples have unique ids, and the RateCache keys its per-day rate dicts by
    // TupleByPeriod{IdAssignmentTuple}, so tuples sharing an id would collide. Value is irrelevant to asserts.
    private static int _nextTupleId = 1;

    public static Ra Ra(int prefix, BigDecimal amount) { return new Ra(prefix, amount); }

    /** Convenience: amount as a decimal string (keeps BigDecimal scale exact, like the C# {@code 1.0m}). */
    public static Ra Ra(int prefix, String amount) { return new Ra(prefix, new BigDecimal(amount)); }

    /** Fluent builder for a {@code rate} ROW. Defaults mirror the C# optional-arg defaults. */
    public static final class Ra {
        private final rate r = new rate();
        private final int prefix;

        Ra(int prefix, BigDecimal amount) {
            this.prefix = prefix;
            r.Prefix = Integer.toString(prefix);
            r.rateamount = amount;
            r.Resolution = 0;
            r.SurchargeTime = 0;
            r.MinDurationSec = 0f;
            r.SurchargeAmount = BigDecimal.ZERO;
            r.idrateplan = 7;
            r.Category = (byte) 1;
            r.SubCategory = (byte) 1;
            r.startdate = MIN;
            r.enddate = null;
            r.Inactive = 0;
            r.OtherAmount1 = BigDecimal.ZERO;   // SF11 IOF / additional charge
            r.OtherAmount3 = BigDecimal.ZERO;   // SF10 VAT fraction / SF11 BTRC fraction
            r.billingspan = null;               // resolve via the rate plan (BillingSpan uom)
        }

        public Ra resolution(int v) { r.Resolution = v; return this; }
        public Ra surchargeTime(int v) { r.SurchargeTime = v; return this; }
        public Ra surchargeAmount(String v) { r.SurchargeAmount = new BigDecimal(v); return this; }
        public Ra minDurationSec(float v) { r.MinDurationSec = v; return this; }
        public Ra idRatePlan(int v) { r.idrateplan = v; return this; }
        public Ra category(int v) { r.Category = (byte) v; return this; }
        public Ra subCategory(int v) { r.SubCategory = (byte) v; return this; }
        public Ra startdate(LocalDateTime v) { r.startdate = v; return this; }
        public Ra enddate(LocalDateTime v) { r.enddate = v; return this; }
        public Ra inactive(int v) { r.Inactive = v; return this; }
        public Ra otherAmount1(float v) { r.OtherAmount1 = new BigDecimal(Float.toString(v)); return this; }
        public Ra otherAmount1(String v) { r.OtherAmount1 = new BigDecimal(v); return this; }
        public Ra otherAmount3(float v) { r.OtherAmount3 = new BigDecimal(Float.toString(v)); return this; }
        public Ra otherAmount3(String v) { r.OtherAmount3 = new BigDecimal(v); return this; }
        public Ra otherAmount9(float v) { r.OtherAmount9 = v; return this; }
        public Ra billingspan(int v) { r.billingspan = v; return this; }
        public Ra rateAmountRoundupDecimal(int v) { r.RateAmountRoundupDecimal = v; return this; }

        int prefix() { return prefix; }
        int idRatePlan() { return r.idrateplan != null ? r.idrateplan : 7; }
        public rate build() { return r; }

        /**
         * A matched {@link Rateext} from this rate row (AssignmentFlag = 0, so P_Startdate()/P_Enddate() are the
         * rate's own startdate/enddate) — for the direct A2ZRater / service-family tests that bypass the loader.
         */
        public Rateext rex() {
            Rateext e = new Rateext();
            e.id = r.id;
            e.Prefix = r.Prefix;
            e.rateamount = r.rateamount;
            e.Resolution = r.Resolution;
            e.MinDurationSec = r.MinDurationSec;
            e.SurchargeTime = r.SurchargeTime;
            e.SurchargeAmount = r.SurchargeAmount;
            e.idrateplan = r.idrateplan;
            e.CountryCode = r.CountryCode;
            e.startdate = r.startdate;
            e.enddate = r.enddate;
            e.Inactive = r.Inactive;
            e.Category = r.Category;
            e.SubCategory = r.SubCategory;
            e.OtherAmount1 = r.OtherAmount1;
            e.OtherAmount3 = r.OtherAmount3;
            e.OtherAmount9 = r.OtherAmount9;
            e.billingspan = r.billingspan;
            e.RateAmountRoundupDecimal = r.RateAmountRoundupDecimal;
            e.AssignmentFlag = 0;
            return e;
        }
    }

    /** A bare tuple (no rates) — for resolver tests, which read only idService/dir/partner/route/priority. */
    public static rateplanassignmenttuple tuple(int idService, int dir, Integer partner, Integer route, int priority) {
        rateplanassignmenttuple t = new rateplanassignmenttuple();
        t.id = _nextTupleId++;
        t.idService = idService;
        t.AssignDirection = dir;
        t.idpartner = partner;
        t.route = route;
        t.priority = priority;
        t.rateassigns = new ArrayList<>();
        return t;
    }

    /**
     * A tuple with ONE rateassign JOIN row pointing to a rate plan (legacy rateassign: Prefix = tuple id,
     * Inactive = idRatePlan, open span) — for the ConfigManagerMapper test, whose rate ROWS arrive separately
     * on the DynamicContext (ratePlanWiseTodaysRates), exactly as config-manager serves them.
     */
    public static rateplanassignmenttuple joinTuple(int idService, int dir, Integer partner, Integer route,
            int priority, int idRatePlan) {
        rateplanassignmenttuple t = tuple(idService, dir, partner, route, priority);
        rateassign join = new rateassign();
        join.Prefix = t.id;
        join.Inactive = idRatePlan;
        join.startdate = MIN;
        join.enddate = null;
        t.rateassigns.add(join);
        return t;
    }

    /** A single default rate plan keyed by id (per-minute billing, no tech prefix / extra rounding). */
    public static Map<String, rateplan> planMap(int id) {
        Map<String, rateplan> m = new java.util.HashMap<>();
        m.put(Integer.toString(id), defaultPlan(id));
        return m;
    }

    /** The standard ofbiz billing-span uom table (uom -> seconds). */
    public static Map<String, enumbillingspan> billingSpans() {
        return standardBillingSpans();
    }

    static rateplan defaultPlan(int id) {
        rateplan rp = new rateplan();
        rp.id = id;
        rp.field4 = "";
        rp.BillingSpan = "TF_min";
        rp.RateAmountRoundupDecimal = null;
        return rp;
    }

    static Map<String, enumbillingspan> standardBillingSpans() {
        Map<String, enumbillingspan> m = new java.util.HashMap<>();
        m.put("TF_s", span("TF_s", 1));
        m.put("TF_min", span("TF_min", 60));
        m.put("TF_hr", span("TF_hr", 3600));
        return m;
    }

    private static enumbillingspan span(String uom, long seconds) {
        enumbillingspan e = new enumbillingspan();
        e.ofbiz_uom_Id = uom;
        e.value = seconds;
        return e;
    }

    public static Fixture fixture() { return new Fixture(); }

    /**
     * Assembles a tenant's rating tables the config-fed RateCache loader joins over: the tuples (each with its
     * rateassign JOIN rows), the rate ROWS per rate plan, the rate plans (DicRatePlan) and the billing-span
     * table. {@link #mediation} folds them into a MediationContext (resolver + per-day RateCache from the SAME
     * data).
     */
    public static final class Fixture {
        public final List<rateplanassignmenttuple> tuples = new ArrayList<>();
        public final Map<Integer, List<rate>> rateRowsByRatePlan = new java.util.HashMap<>();
        public final Map<String, rateplan> dicRatePlan = new java.util.HashMap<>();
        public final Map<String, enumbillingspan> billingSpans = standardBillingSpans();
        public int maxDecimalPrecision = 8;

        /**
         * Register a tuple whose rateassign JOIN rows point to the rate plan(s) of the given rate ROWS; the rate
         * rows go to {@code rateRowsByRatePlan[plan]} and a default rate plan is registered per plan id.
         */
        public rateplanassignmenttuple tup(int idService, int dir, Integer partner, Integer route, int priority,
                Ra... rates) {
            rateplanassignmenttuple t = tuple(idService, dir, partner, route, priority);

            LinkedHashMap<Integer, List<rate>> byPlan = new LinkedHashMap<>();
            for (Ra ra : rates) {
                int plan = ra.idRatePlan();
                byPlan.computeIfAbsent(plan, k -> new ArrayList<>()).add(ra.build());
            }
            if (byPlan.isEmpty()) byPlan.put(7, new ArrayList<>());

            for (var e : byPlan.entrySet()) {
                int plan = e.getKey();
                rateassign join = new rateassign();
                join.Prefix = t.id;             // legacy: rateassign.prefix = tuple id
                join.Inactive = plan;           // legacy: Inactive = idRatePlan
                join.startdate = MIN;
                join.enddate = null;
                t.rateassigns.add(join);
                rateRowsByRatePlan.computeIfAbsent(plan, k -> new ArrayList<>()).addAll(e.getValue());
                dicRatePlan.computeIfAbsent(Integer.toString(plan), k -> defaultPlan(plan));
            }
            tuples.add(t);
            return t;
        }

        public MediationContext mediation() {
            return mediation(null, null, null, null);
        }

        public MediationContext mediation(Map<Integer, ServiceCategory> categories, List<ServiceGroupRule> sgRules,
                Map<Integer, ServiceGroupConfiguration> sgConfigs, List<IValidationRule<cdr>> commonChecklist) {
            return MediationContext.ForRating(tuples, rateRowsByRatePlan, dicRatePlan, billingSpans,
                    maxDecimalPrecision, categories, sgRules, sgConfigs, commonChecklist);
        }

        public RateCache rateCache() {
            return mediation().RateCache;
        }

        /** The TupleByPeriod list for the given day (the shape PrefixMatcher consumes). */
        public List<TupleByPeriod> tupsForDay(DateRange day) {
            return tuples.stream().map(t -> {
                TupleByPeriod tp = new TupleByPeriod();
                tp.IdAssignmentTuple = t.id;
                tp.DRange = day;
                tp.Priority = t.priority;
                return tp;
            }).collect(Collectors.toList());
        }
    }
}
