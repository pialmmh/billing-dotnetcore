package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression pins for the 2026-09-03 SG15 Hong Kong incident: the international plan (117) carries
 * techPrefix "00" ({@code rateplan.field4}), so {@link TupleRateLoader} keys every rate as
 * {@code "00" + prefix} ('852' -&gt; '00852'). Only the number AS DIALED ({@code 0085228866016}) can match;
 * the 00-STRIPPED form ({@code 85228866016}) silently matches nothing — which sent every answered
 * international call to cdrerror ("NO rate (idService=7)"). BasicCharge.RateIntlOut must therefore match
 * with the dialed number, never the stripped one.
 */
class TechPrefixMatchTests {

    private static final LocalDateTime Answer = LocalDateTime.of(2026, 9, 3, 17, 55, 10);
    private static final DateRange Day = new DateRange(
            Answer.toLocalDate().atStartOfDay(), Answer.toLocalDate().atStartOfDay().plusDays(1));

    private static RateCache cacheWithIntlPlan(String techPrefix) {
        rate hk = new rate();
        hk.id = 6600740L;
        hk.Prefix = "852";
        hk.rateamount = new BigDecimal("12.0");
        hk.idrateplan = 117;
        hk.Category = 1;
        hk.SubCategory = 1;
        hk.startdate = LocalDateTime.of(2025, 7, 4, 0, 0);
        hk.enddate = null;

        rateplan plan = new rateplan();
        plan.id = 117;
        plan.RatePlanName = "Outgoing XYZ @IGW New Rate";
        plan.field4 = techPrefix;                              // the tech prefix ("00" in production)

        rateplanassignmenttuple tuple = new rateplanassignmenttuple();
        tuple.id = 26;
        tuple.idService = 7;
        tuple.AssignDirection = 0;
        tuple.priority = 1;
        rateassign join = new rateassign();
        join.Prefix = 26;                                      // FK to the tuple
        join.Inactive = 117;                                   // legacy quirk: Inactive holds the idRatePlan
        join.startdate = LocalDateTime.of(2000, 1, 1, 0, 0);
        join.enddate = null;
        tuple.rateassigns = new ArrayList<>(List.of(join));

        var loader = new TupleRateLoader(List.of(tuple), Map.of(117, List.of(hk)), Map.of("117", plan));
        return new RateCache(loader);
    }

    private static TupleByPeriod tuple26() {
        TupleByPeriod tp = new TupleByPeriod();
        tp.IdAssignmentTuple = 26;
        tp.DRange = Day;
        tp.Priority = 1;
        return tp;
    }

    /** The dialed number (00-kept) MUST match the techPrefix-keyed '00852' rate. */
    @Test
    void dialed_number_matches_techprefixed_intl_rate() {
        Rateext hit = new PrefixMatcher(cacheWithIntlPlan("00"), "0085228866016", 1, 1,
                List.of(tuple26()), Answer).MatchPrefix();
        assertNotNull(hit, "dialed 0085228866016 must match key '00852' (techPrefix 00 + prefix 852)");
        assertEquals("852", hit.Prefix);
        assertEquals(0, new BigDecimal("12.0").compareTo(hit.rateamount));
    }

    /** The 00-stripped form matches NOTHING when the plan carries techPrefix 00 — the exact production bug. */
    @Test
    void stripped_number_matches_nothing_documenting_the_incident() {
        Rateext hit = new PrefixMatcher(cacheWithIntlPlan("00"), "85228866016", 1, 1,
                List.of(tuple26()), Answer).MatchPrefix();
        assertNull(hit, "stripped 85228866016 cannot reach key '00852' — SG15 must pass the DIALED number");
    }

    /** A plan with NO tech prefix keys rates bare — then the bare number matches (both behaviours pinned). */
    @Test
    void empty_techprefix_keys_rates_bare() {
        Rateext hit = new PrefixMatcher(cacheWithIntlPlan(""), "85228866016", 1, 1,
                List.of(tuple26()), Answer).MatchPrefix();
        assertNotNull(hit);
        assertEquals("852", hit.Prefix);
    }
}
