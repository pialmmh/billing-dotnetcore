package com.telcobright.billing.tenantconfigsync.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.tenantconfigsync.internal.dto.RateAssignDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RatePlanAssignmentTupleDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE verification of Fix B against a REAL config-manager payload (the nested-tuple shape). Skipped unless a
 * saved tenant-root JSON is provided via {@code -Dbilling.tenantJson=<path>} (e.g. the response of
 * {@code POST /get-specific-tenant-root?name=ccl78}). Proves that running the actual {@link ConfigManagerMapper}
 * over the served shape yields a working RatePlanResolver + RateCache that prices a real prefix — i.e. the
 * historically-flagged "no rate or package" gap is closed.
 */
class ConfigManagerLiveRateTests {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void live_payload_prices_a_prefix_via_synthesized_tuples() throws Exception {
        String path = System.getProperty("billing.tenantJson");
        Assumptions.assumeTrue(path != null && new File(path).exists(),
                "set -Dbilling.tenantJson=<saved get-specific-tenant-root response> to run");

        TenantDto dto = JSON.readValue(new File(path), TenantDto.class);
        Tenant root = ConfigManagerMapper.ToTenant(dto);   // telcobright (the served root)

        MediationContext mc = root.Context.MediationContext;

        // telcobright customer tuple: SG10, partner 78 -> its plan's rates. Synthesized from the nested tuples.
        List<rateplanassignmenttuple> tuples =
                mc.RatePlanResolver.Resolve(10, AssignmentDirection.Customer.value, 78, null);
        System.out.println("[live] " + root.DbName + " tuples for (SG10,customer,partner78) = " + tuples.size());
        assertFalse(tuples.isEmpty(), "partner 78 must resolve a synthesized tuple (Fix B)");

        LocalDateTime answer = LocalDateTime.of(2026, 6, 17, 13, 34, 43);
        Rateext rex = priced(mc, tuples, "8801789896378", answer);
        System.out.println("[live] priced 8801789896378 -> prefix=" + (rex != null ? rex.Prefix : null)
                + " amount=" + (rex != null ? rex.rateamount : null));
        assertNotNull(rex, "the RateCache must price a domestic 880 number for partner 78");
    }

    private static final LocalDateTime ANSWER = LocalDateTime.of(2026, 6, 17, 13, 34, 43);
    private static final String DIALED = "8801789896378";   // domestic BD number (prefix 880)

    @Test
    void live_fetch_rate_for_all_tenants() throws Exception {
        String path = System.getProperty("billing.tenantJson");
        Assumptions.assumeTrue(path != null && new File(path).exists(),
                "set -Dbilling.tenantJson=<saved get-specific-tenant-root response> to run");

        TenantDto dto = JSON.readValue(new File(path), TenantDto.class);
        Tenant root = ConfigManagerMapper.ToTenant(dto);

        int[] totals = new int[2];   // [priceable, total]
        report(dto, root, totals);
        System.out.printf("%n[all-tenants] priceable rate-assignments (via each tuple's own idService) = %d/%d%n",
                totals[0], totals[1]);
        // Fix B must fetch SOME rate somewhere in the tree.
        assertTrue(totals[0] > 0, "Fix B must resolve+price at least one configured rate across the tree");
    }

    /** For each tenant node: resolve+price every distinct configured tuple (using its OWN idService/dir/partner),
     * proving the rates are fetched. Also flags tuples whose idService != the ServiceGroup id the live rater
     * uses (10/11) — those won't match live SG detection (a separate config question, not a Fix B defect). */
    private void report(TenantDto dto, Tenant mapped, int[] totals) {
        MediationContext mc = mapped.Context.MediationContext;
        System.out.println("\n===== " + mapped.DbName + " =====");

        Map<Integer, RatePlanAssignmentTupleDto> tuplesById = new LinkedHashMap<>();
        if (dto.Context != null) {
            collect(dto.Context.RateAssignsCustomer, tuplesById);
            collect(dto.Context.RateAssignsSupplier, tuplesById);
        }
        if (tuplesById.isEmpty()) System.out.println("  (no rate assignments served)");

        for (RatePlanAssignmentTupleDto tup : tuplesById.values()) {
            totals[1]++;
            List<rateplanassignmenttuple> tuples =
                    mc.RatePlanResolver.Resolve(tup.idService, tup.assignDirection, tup.idPartner, tup.route);
            // the cdr carries its own category/subCategory; try the ones the served rates use (voice=1, dom-out=10).
            Rateext rex = null;
            for (int[] cat : new int[][]{{1, 1}, {10, 1}, {11, 1}}) {
                rex = tuples.isEmpty() ? null : priced(mc, tuples, DIALED, ANSWER, cat[0], cat[1]);
                if (rex != null) break;
            }
            if (rex != null) totals[0]++;
            String priced = rex != null ? (rex.Prefix + " = " + rex.rateamount)
                                        : (tuples.isEmpty() ? "NO TUPLE RESOLVED" : "resolved but no priced rate");
            String sgFlag = (tup.idService == 10 || tup.idService == 11)
                    ? "" : "   [idService=" + tup.idService + " != SG10/11 -> won't match live SG detection]";
            System.out.printf("  tuple#%-4d dir=%d partner=%-5s idService=%-3d  %-24s%s%n",
                    tup.id, tup.assignDirection, String.valueOf(tup.idPartner), tup.idService, priced, sgFlag);
        }

        // recurse the tree (dto + mapped share child keys)
        if (dto.Children != null)
            for (Map.Entry<String, TenantDto> e : dto.Children.entrySet()) {
                Tenant child = mapped.Children.get(e.getKey());
                if (child != null) report(e.getValue(), child, totals);
            }
    }

    private static void collect(List<RateAssignDto> assigns, Map<Integer, RatePlanAssignmentTupleDto> out) {
        if (assigns == null) return;
        for (RateAssignDto ra : assigns)
            if (ra.ratePlanAssignmentTuple != null) out.putIfAbsent(ra.ratePlanAssignmentTuple.id, ra.ratePlanAssignmentTuple);
    }

    private static Rateext priced(MediationContext mc, List<rateplanassignmenttuple> tuples,
            String dialed, LocalDateTime answer) {
        return priced(mc, tuples, dialed, answer, 1, 1);
    }

    private static Rateext priced(MediationContext mc, List<rateplanassignmenttuple> tuples,
            String dialed, LocalDateTime answer, int category, int subCategory) {
        DateRange day = new DateRange(answer.toLocalDate().atStartOfDay(),
                answer.toLocalDate().atStartOfDay().plusDays(1));
        List<TupleByPeriod> tups = tuples.stream().map(t -> {
            TupleByPeriod tp = new TupleByPeriod();
            tp.IdAssignmentTuple = t.id;
            tp.DRange = day;
            tp.Priority = t.priority;
            return tp;
        }).collect(Collectors.toList());
        return new PrefixMatcher(mc.RateCache, dialed, category, subCategory, tups, answer).MatchPrefix();
    }
}
