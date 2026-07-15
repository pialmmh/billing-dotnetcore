package com.telcobright.billing.tenantconfigsync.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE verification (skipped unless {@code -Dbilling.tenantJson=<saved get-specific-tenant-root response>}):
 * runs the REAL config-manager client mapper config over a real payload to prove two things on the
 * date-cache branch after Fix B was re-applied: (1) the tenant-root DESERIALIZES (the
 * {@code endPreviousRate:false}→Byte crash is gone, since rateAssigns bind to {@code RateAssignDto}), and
 * (2) rates RESOLVE per tenant (tuples synthesized from the nested shape) and the RateCache prices them.
 */
class ConfigManagerLiveRateTests {

    // EXACTLY the HttpConfigManagerClient mapper config — NO Boolean->Byte coercion. If this deserializes, the
    // real client deserializes.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void live_payload_deserializes_and_prices_per_tenant() throws Exception {
        String path = System.getProperty("billing.tenantJson");
        Assumptions.assumeTrue(path != null && new File(path).exists(),
                "set -Dbilling.tenantJson=<saved get-specific-tenant-root response> to run");

        JsonNode rootJson = JSON.readTree(new File(path));
        // (1) deserialize with the real mapper config — must NOT throw (the Byte crash is gone).
        Tenant root = ConfigManagerMapper.ToTenant(JSON.treeToValue(rootJson, TenantDto.class));

        System.out.println("\n===== per-tenant rate fetch (date-cache branch + Fix B re-applied) =====");
        int[] totals = new int[2];
        walk(rootJson, root, totals);
        System.out.printf("%n[RESULT] rate-assignments that RESOLVE+PRICE = %d / %d across all tenants%n",
                totals[0], totals[1]);

        // (2) the system can now fetch rates.
        assertTrue(totals[0] > 0, "after re-applying Fix B the system must fetch rates for some tenant");
    }

    private void walk(JsonNode json, Tenant mapped, int[] totals) {
        String db = json.path("dbName").asText("?");
        MediationContext mc = mapped.Context.MediationContext;
        int resolved = 0, priced = 0, total = 0;
        JsonNode ctx = json.path("context");
        for (String side : new String[]{"rateAssignsCustomer", "rateAssignsSupplier"}) {
            for (JsonNode ra : ctx.path(side)) {
                JsonNode tup = ra.path("ratePlanAssignmentTuple");
                if (tup.isMissingNode() || tup.isNull()) continue;
                total++;
                int idService = tup.path("idService").asInt();
                int dir = tup.path("assignDirection").asInt();
                Integer partner = tup.hasNonNull("idPartner") ? tup.path("idPartner").asInt() : null;
                Integer route = tup.hasNonNull("route") ? tup.path("route").asInt() : null;
                List<rateplanassignmenttuple> tuples = mc.RatePlanResolver.Resolve(idService, dir, partner, route);
                if (!tuples.isEmpty()) resolved++;
                if (priceAny(mc, tuples, "8801789896378") != null) priced++;
                if (partner == null && route == null)
                    System.out.printf("    [service-wide] idService=%d dir=%d -> %s%n",
                            idService, dir, tuples.isEmpty() ? "NOT resolved" : "RESOLVED (" + tuples.size() + ")");
            }
        }
        System.out.printf("  %-14s resolved %d / %d, priced %d / %d%n", db, resolved, total, priced, total);
        totals[0] += priced;
        totals[1] += total;

        Iterator<Map.Entry<String, JsonNode>> kids = json.path("children").fields();
        while (kids.hasNext()) {
            Map.Entry<String, JsonNode> e = kids.next();
            Tenant child = mapped.Children.get(e.getKey());
            if (child != null) walk(e.getValue(), child, totals);
        }
    }

    // price the dialed number for TODAY (the branch pre-warms today's rows); try the categories the rates use.
    private static Rateext priceAny(MediationContext mc, List<rateplanassignmenttuple> tuples, String dialed) {
        if (tuples.isEmpty()) return null;
        java.time.LocalDateTime answer = LocalDate.now().atTime(13, 34, 43);
        DateRange day = new DateRange(answer.toLocalDate().atStartOfDay(), answer.toLocalDate().atStartOfDay().plusDays(1));
        List<TupleByPeriod> tups = tuples.stream().map(t -> {
            TupleByPeriod tp = new TupleByPeriod();
            tp.IdAssignmentTuple = t.id;
            tp.DRange = day;
            tp.Priority = t.priority;
            return tp;
        }).collect(Collectors.toList());
        for (int[] cat : new int[][]{{1, 1}, {10, 1}, {11, 1}}) {
            Rateext rex = new PrefixMatcher(mc.RateCache, dialed, cat[0], cat[1], tups, answer).MatchPrefix();
            if (rex != null) return rex;
        }
        return null;
    }
}
