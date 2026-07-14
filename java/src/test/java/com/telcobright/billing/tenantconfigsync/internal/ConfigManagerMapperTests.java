package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.model.RatePlan;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RateAssignDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RatePlanAssignmentTupleDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config-manager → Model mapping, focused on the rating wiring: a tenant's legacy
 * rateplanassignmenttuples (each with its rateassign JOIN rows) must arrive as a working RatePlanResolver +
 * per-day RateCache folded inside its MediationContext, with the actual rate ROWS + rate plans joined in from
 * the DynamicContext (ratePlanWiseTodaysRates / ratePlans) — the config-fed legacy JOIN. The last two tests
 * prove the now-FULL served shape flows: the served BillingSpan uom (resolved via the served billingSpans
 * table) and a served OtherAmount9 (fraction-ceiling) both change the A2Z amount.
 */
class ConfigManagerMapperTests {

    private static TenantDto TenantWith(DynamicContextDto ctx) {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";
        dto.Context = ctx;
        return dto;
    }

    // A served rate row (the camelCase JSON config-manager sends, here built directly). Voice category 1; the
    // rest left at their unset defaults so per-test tweaks (OtherAmount9, billing span) stand out.
    private static Rate servedRate(long id, String prefix, int idRatePlan, String amount) {
        Rate r = new Rate();
        r.Id = id;
        r.Prefix = prefix;
        r.IdRatePlan = idRatePlan;
        r.RateAmount = new BigDecimal(amount);
        r.CountryCode = prefix;
        r.Category = 1;
        r.Resolution = 0;
        r.MinDurationSec = BigDecimal.ZERO;
        r.SurchargeTime = 0;
        r.SurchargeAmount = BigDecimal.ZERO;
        r.Inactive = 0;
        return r;
    }

    // A served rate plan. billingSpanUom == null lets the mapper apply its TF_min default.
    private static RatePlan servedPlan(int id, String name, String billingSpanUom) {
        RatePlan p = new RatePlan();
        p.Id = id;
        p.Name = name;
        p.BillingSpan = billingSpanUom;
        return p;
    }

    // A served billing-span row: only the seconds value carries (the map KEY is the uom the rater looks up;
    // config-manager may leave ofbiz_uom_Id/Type null on the wire).
    private static enumbillingspan span(long seconds) {
        enumbillingspan e = new enumbillingspan();
        e.value = seconds;
        return e;
    }

    // Resolve partner 5's tuples on the answer day and longest-prefix-match the dialed number — the runtime
    // path, used to pull the priced Rateext the engine would rate.
    private static Rateext matchedRate(MediationContext mc, int idService, int dir, Integer partner,
            String dialed, LocalDateTime answer) {
        List<rateplanassignmenttuple> tuples = mc.RatePlanResolver.Resolve(idService, dir, partner, null);
        DateRange day = new DateRange(answer.toLocalDate().atStartOfDay(),
                answer.toLocalDate().atStartOfDay().plusDays(1));
        List<TupleByPeriod> tups = tuples.stream().map(t -> {
            TupleByPeriod tp = new TupleByPeriod();
            tp.IdAssignmentTuple = t.id;
            tp.DRange = day;
            tp.Priority = t.priority;
            return tp;
        }).collect(Collectors.toList());
        return new PrefixMatcher(mc.RateCache, dialed, 1, 1, tups, answer).MatchPrefix();
    }

    @Test
    void RatePlanResolver_is_built_from_the_tuples() {
        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.joinTuple(10, AssignmentDirection.Customer.value, 5, null, 0, 7));
        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;

        Tenant tenant = ConfigManagerMapper.ToTenant(TenantWith(ctx));
        List<rateplanassignmenttuple> tuples =
                tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, 5, null);

        assertEquals(1, tuples.size());
        // the join rateassign carries the idRatePlan in its Inactive field (legacy quirk).
        assertEquals(7, tuples.get(0).rateassigns.get(0).Inactive);
    }

    // A served rate-assign carrying its tuple NESTED (config-manager's actual shape), pointing at a rate plan.
    private static RateAssignDto servedAssign(int tupleId, int idService, int dir, Integer partner, Integer route,
            int priority, int idRatePlan) {
        RateAssignDto ra = new RateAssignDto();
        ra.inactive = idRatePlan;                 // legacy: rateassign.Inactive holds the idRatePlan
        ra.startDate = LocalDateTime.of(2000, 1, 1, 0, 0);
        ra.endDate = null;
        ra.ratePlan = new RateAssignDto.RatePlanRef();
        ra.ratePlan.id = idRatePlan;
        RatePlanAssignmentTupleDto tup = new RatePlanAssignmentTupleDto();
        tup.id = tupleId;
        tup.idService = idService;
        tup.assignDirection = dir;
        tup.idPartner = partner;
        tup.route = route;
        tup.priority = priority;
        ra.ratePlanAssignmentTuple = tup;
        return ra;
    }

    @Test
    void nested_tuples_in_rate_assigns_are_synthesized_when_flat_list_absent() {
        // config-manager's LIVE shape: NO flat mediationContext.ratePlanAssignmentTuples — the tuple is nested
        // inside each served rateAssignsCustomer row (partner 78 -> plan 1, SG10 customer).
        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = new MediationContextDto();     // categories/tuples absent, like live
        ctx.RateAssignsCustomer = List.of(servedAssign(1, 10, AssignmentDirection.Customer.value, 78, null, 1, 1));
        ctx.RatePlans = Map.of(1, servedPlan(1, "Domestic Out", null));
        ctx.RatePlanWiseTodaysRates = Map.of(1, Map.of(
                "880", servedRate(57299, "880", 1, "0.40"),
                "8801", servedRate(2, "8801", 1, "0.50")));

        MediationContext mc = ConfigManagerMapper.ToTenant(TenantWith(ctx)).Context.MediationContext;

        // the resolver now finds partner 78's synthesized tuple…
        List<rateplanassignmenttuple> tuples = mc.RatePlanResolver.Resolve(10, AssignmentDirection.Customer.value, 78, null);
        assertEquals(1, tuples.size());
        assertEquals(1, tuples.get(0).id);
        assertEquals(1, tuples.get(0).rateassigns.get(0).Inactive);   // idRatePlan via the Inactive quirk

        // …and the RateCache prices the dialed number (longest prefix wins).
        LocalDateTime answer = LocalDateTime.of(2026, 6, 17, 13, 34);
        Rateext longest = matchedRate(mc, 10, AssignmentDirection.Customer.value, 78, "8801789896378", answer);
        assertEquals("8801", longest.Prefix);
        assertEquals(0, new BigDecimal("0.50").compareTo(longest.rateamount));

        Rateext shorter = matchedRate(mc, 10, AssignmentDirection.Customer.value, 78, "88099999999", answer);
        assertEquals("880", shorter.Prefix);
        assertEquals(0, new BigDecimal("0.40").compareTo(shorter.rateamount));
    }

    @Test
    void flat_tuples_take_precedence_over_nested_when_both_present() {
        // if config-manager DOES serve the flat list (Fix A), use it; the nested rate-assigns are not re-synthesized.
        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.joinTuple(10, AssignmentDirection.Customer.value, 5, null, 0, 7));

        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;
        ctx.RateAssignsCustomer = List.of(servedAssign(1, 10, AssignmentDirection.Customer.value, 78, null, 1, 1));
        ctx.RatePlans = Map.of(7, servedPlan(7, "plan7", null));
        ctx.RatePlanWiseTodaysRates = Map.of(7, Map.of("880", servedRate(1, "880", 7, "0.35")));

        MediationContext mc = ConfigManagerMapper.ToTenant(TenantWith(ctx)).Context.MediationContext;
        assertFalse(mc.RatePlanResolver.Resolve(10, 1, 5, null).isEmpty());   // flat partner 5 present
        assertTrue(mc.RatePlanResolver.Resolve(10, 1, 78, null).isEmpty());   // nested partner 78 NOT synthesized
    }

    @Test
    void Empty_context_yields_an_empty_resolver() {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";
        Tenant tenant = ConfigManagerMapper.ToTenant(dto);
        assertTrue(tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, 5, null).isEmpty());
    }

    @Test
    void Tuples_plus_rate_rows_plus_plans_become_a_priced_ratecache() {
        // config-manager serves: the JOIN tuple (partner 5 -> rate plan 7), the rate ROWS for plan 7, and plan 7.
        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.joinTuple(10, AssignmentDirection.Customer.value, 5, null, 0, 7));

        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;
        ctx.RatePlans = Map.of(7, servedPlan(7, "plan7", null));
        ctx.RatePlanWiseTodaysRates = Map.of(7, Map.of(
                "880", servedRate(1, "880", 7, "0.35"),
                "8801", servedRate(2, "8801", 7, "0.40")));

        Tenant tenant = ConfigManagerMapper.ToTenant(TenantWith(ctx));
        MediationContext mc = tenant.Context.MediationContext;

        // the resolver returns partner 5's plan-7 tuple…
        List<rateplanassignmenttuple> tuples = mc.RatePlanResolver.Resolve(10, AssignmentDirection.Customer.value, 5, null);
        assertFalse(tuples.isEmpty());

        // …and the RateCache prices the dialed number by longest-prefix over plan 7's rate rows.
        LocalDateTime answer = LocalDateTime.of(2026, 6, 19, 12, 0);
        DateRange day = new DateRange(answer.toLocalDate().atStartOfDay(), answer.toLocalDate().atStartOfDay().plusDays(1));
        List<TupleByPeriod> tups = tuples.stream().map(t -> {
            TupleByPeriod tp = new TupleByPeriod();
            tp.IdAssignmentTuple = t.id;
            tp.DRange = day;
            tp.Priority = t.priority;
            return tp;
        }).collect(Collectors.toList());

        Rateext longest = new PrefixMatcher(mc.RateCache, "88017123456", 1, 1, tups, answer).MatchPrefix();
        assertEquals("8801", longest.Prefix);                                       // longest prefix wins
        assertEquals(0, new BigDecimal("0.40").compareTo(longest.rateamount));

        Rateext shorter = new PrefixMatcher(mc.RateCache, "88099999999", 1, 1, tups, answer).MatchPrefix();
        assertEquals("880", shorter.Prefix);
        assertEquals(0, new BigDecimal("0.35").compareTo(shorter.rateamount));
    }

    @Test
    void Served_billingSpan_uom_resolves_via_the_served_billingSpans_table() {
        // config-manager serves a CUSTOM uom "TF_5s" = 5s (NOT in the built-in StandardBillingSpans) and a plan
        // whose BillingSpan points at it. Reaching a priced amount PROVES the served billingSpans map (not the
        // hardcoded fallback) is threaded through — the fallback does not know "TF_5s" and would throw.
        LocalDateTime answer = LocalDateTime.of(2026, 6, 19, 12, 0);

        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.joinTuple(10, AssignmentDirection.Customer.value, 5, null, 0, 7));
        Map<String, enumbillingspan> servedSpans = new HashMap<>();
        servedSpans.put("TF_5s", span(5));
        mediation.BillingSpans = servedSpans;

        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;
        ctx.RatePlans = Map.of(7, servedPlan(7, "plan7", "TF_5s"));
        ctx.RatePlanWiseTodaysRates = Map.of(7, Map.of("880", servedRate(1, "880", 7, "1.0")));

        MediationContext mc = ConfigManagerMapper.ToTenant(TenantWith(ctx)).Context.MediationContext;
        Rateext rex = matchedRate(mc, 10, AssignmentDirection.Customer.value, 5, "8801234567", answer);
        assertEquals("880", rex.Prefix);

        // 60s call at 1.0 per 5s => 60 * (1.0/5) = 12.0.
        BigDecimal served = A2ZRater.Rate(rex, new BigDecimal("60"), mc.DicRatePlan, mc.BillingSpans,
                mc.MaxDecimalPrecision).Amount();
        assertEquals(0, new BigDecimal("12").compareTo(served), "served TF_5s(=5s) must bill per 5 seconds");

        // contrast: the TF_min(=60s) default yields a DIFFERENT amount (1.0) — proving billing span flows.
        // (no served BillingSpans here, so the mapper falls back to StandardBillingSpans, which has TF_min=60.)
        MediationContextDto mediationDefault = new MediationContextDto();
        mediationDefault.RatePlanAssignmentTuples = mediation.RatePlanAssignmentTuples;
        DynamicContextDto ctxDefault = new DynamicContextDto();
        ctxDefault.MediationContext = mediationDefault;
        ctxDefault.RatePlans = Map.of(7, servedPlan(7, "plan7", "TF_min"));
        ctxDefault.RatePlanWiseTodaysRates = ctx.RatePlanWiseTodaysRates;
        MediationContext mcDefault = ConfigManagerMapper.ToTenant(TenantWith(ctxDefault)).Context.MediationContext;
        Rateext rexDefault = matchedRate(mcDefault, 10, AssignmentDirection.Customer.value, 5, "8801234567", answer);
        BigDecimal dft = A2ZRater.Rate(rexDefault, new BigDecimal("60"), mcDefault.DicRatePlan,
                mcDefault.BillingSpans, mcDefault.MaxDecimalPrecision).Amount();
        assertEquals(0, new BigDecimal("1").compareTo(dft));
        assertNotEquals(0, served.compareTo(dft));
    }

    @Test
    void Served_otherAmount9_triggers_the_fraction_ceiling() {
        LocalDateTime answer = LocalDateTime.of(2026, 6, 19, 12, 0);
        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.joinTuple(10, AssignmentDirection.Customer.value, 5, null, 0, 7));

        // a rate carrying a SERVED OtherAmount9 = 2 (fraction-ceiling at the 2nd decimal position).
        Rate ceil = servedRate(1, "880", 7, "0.4");
        ceil.OtherAmount9 = 2f;
        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;
        ctx.RatePlans = Map.of(7, servedPlan(7, "plan7", null));   // TF_min default => 60s
        ctx.RatePlanWiseTodaysRates = Map.of(7, Map.of("880", ceil));

        MediationContext mc = ConfigManagerMapper.ToTenant(TenantWith(ctx)).Context.MediationContext;
        Rateext rex = matchedRate(mc, 10, AssignmentDirection.Customer.value, 5, "8801234567", answer);
        assertEquals("880", rex.Prefix);
        // 7s call at 0.4 per 60s => 7 * (0.4/60) = 0.04666667; ceiled at position 2 => 0.05.
        BigDecimal ceiled = A2ZRater.Rate(rex, new BigDecimal("7"), mc.DicRatePlan, mc.BillingSpans,
                mc.MaxDecimalPrecision).Amount();
        assertEquals(0, new BigDecimal("0.05").compareTo(ceiled), "OtherAmount9=2 must ceil 0.04666667 -> 0.05");

        // contrast: no OtherAmount9 served => no ceiling => the raw HALF_EVEN-rounded amount.
        Rate raw = servedRate(1, "880", 7, "0.4");   // OtherAmount9 left null
        DynamicContextDto ctx2 = new DynamicContextDto();
        ctx2.MediationContext = mediation;
        ctx2.RatePlans = Map.of(7, servedPlan(7, "plan7", null));
        ctx2.RatePlanWiseTodaysRates = Map.of(7, Map.of("880", raw));
        MediationContext mc2 = ConfigManagerMapper.ToTenant(TenantWith(ctx2)).Context.MediationContext;
        Rateext rex2 = matchedRate(mc2, 10, AssignmentDirection.Customer.value, 5, "8801234567", answer);
        BigDecimal noCeil = A2ZRater.Rate(rex2, new BigDecimal("7"), mc2.DicRatePlan, mc2.BillingSpans,
                mc2.MaxDecimalPrecision).Amount();
        assertEquals(0, new BigDecimal("0.04666667").compareTo(noCeil));
        assertNotEquals(0, ceiled.compareTo(noCeil));
    }
}
