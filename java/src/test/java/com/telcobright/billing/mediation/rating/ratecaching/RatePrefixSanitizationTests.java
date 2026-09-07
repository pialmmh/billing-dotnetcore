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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression pins for the 2026-09-07 res_261 incident: all four rate rows of the reseller's plan were seeded on
 * the master with a trailing {@code 0x1F} on {@code Prefix} ({@code '880'} stored as {@code 3838301F}). Because
 * {@link TupleRateLoader} keys the cache on {@code techPrefix + Prefix}, those rates became invisible — every
 * reseller call died as {@code RATE_NOT_FOUND} while a plain {@code SELECT Prefix} looked correct.
 *
 * <p>{@link TupleRateLoader#SanitizePrefix} now strips control/whitespace characters (and WARNs once per
 * distinct bad value) so corrupt config degrades loudly instead of silently zero-matching. The guard must never
 * make a rate match MORE than the clean data would, which the null-prefix case below pins.
 *
 * <p>See {@link TechPrefixMatchTests} for the other half of the key (the SG15 tech-prefix incident).
 */
class RatePrefixSanitizationTests {

    private static final char Us = 0x1F;                       // ASCII 31, Unit Separator — the observed junk
    private static final LocalDateTime Answer = LocalDateTime.of(2026, 9, 7, 15, 2, 16);
    private static final DateRange Day = new DateRange(
            Answer.toLocalDate().atStartOfDay(), Answer.toLocalDate().atStartOfDay().plusDays(1));

    /** res_261's plan 1 ("0.50_user1") with ONE rate row, whose Prefix/techPrefix are supplied by the caller. */
    private static RateCache cacheWith(String ratePrefix, String techPrefix) {
        rate r = new rate();
        r.id = 2L;
        r.Prefix = ratePrefix;
        r.rateamount = new BigDecimal("0.50");
        r.idrateplan = 1;
        r.Category = 1;
        r.SubCategory = 1;
        r.startdate = LocalDateTime.of(2026, 8, 6, 17, 57, 23);
        r.enddate = null;

        rateplan plan = new rateplan();
        plan.id = 1;
        plan.RatePlanName = "0.50_user1";
        plan.field4 = techPrefix;

        rateplanassignmenttuple tuple = new rateplanassignmenttuple();
        tuple.id = 1;
        tuple.idService = 10;
        tuple.AssignDirection = 1;                             // Customer
        tuple.priority = 1;
        rateassign join = new rateassign();
        join.Prefix = 1;                                       // FK to the tuple
        join.Inactive = 1;                                     // legacy quirk: Inactive holds the idRatePlan
        join.startdate = LocalDateTime.of(2026, 8, 6, 18, 1, 17);
        join.enddate = null;
        tuple.rateassigns = new ArrayList<>(List.of(join));

        return new RateCache(new TupleRateLoader(List.of(tuple), Map.of(1, List.of(r)), Map.of("1", plan)));
    }

    private static TupleByPeriod tuple1() {
        TupleByPeriod tp = new TupleByPeriod();
        tp.IdAssignmentTuple = 1;
        tp.DRange = Day;
        tp.Priority = 1;
        return tp;
    }

    private static Rateext match(RateCache cache, String dialed) {
        return new PrefixMatcher(cache, dialed, 1, 1, List.of(tuple1()), Answer).MatchPrefix();
    }

    /** THE INCIDENT: a trailing 0x1F on the rate's Prefix must no longer hide the rate. */
    @Test
    void control_char_on_rate_prefix_still_matches() {
        Rateext hit = match(cacheWith("880" + Us, ""), "8801761625306");
        assertNotNull(hit, "'880\\x1F' must be scrubbed to '880' and still match the dialed number");
        assertEquals("880", hit.Prefix,
                "the CLEANED prefix must be what flows on to acc_chargeable.Prefix / cdr.MatchedPrefixCustomer");
    }

    /** The other half of the key: junk on rateplan.field4 (techPrefix) is scrubbed the same way. */
    @Test
    void control_char_on_techprefix_still_matches() {
        Rateext hit = match(cacheWith("852", "00" + Us), "0085228866016");
        assertNotNull(hit, "techPrefix '00\\x1F' must be scrubbed to '00' so the key is '00852'");
        assertEquals("852", hit.Prefix);
    }

    /** Surrounding whitespace is junk too — a padded prefix must behave like the trimmed one. */
    @Test
    void whitespace_around_prefix_is_stripped() {
        assertNotNull(match(cacheWith(" 880 ", ""), "8801761625306"));
    }

    /** Clean data must be passed through byte-for-byte (no copy, no change). */
    @Test
    void clean_prefix_is_untouched() {
        assertEquals("880", TupleRateLoader.SanitizePrefix("880", "test"));
        assertEquals("+880", TupleRateLoader.SanitizePrefix("+880", "test"));
        assertEquals("", TupleRateLoader.SanitizePrefix("", "test"));
    }

    /**
     * The guard must never widen a rate's reach. A null Prefix keys as the literal "…null" and matches nothing;
     * scrubbing it to "" would promote that corrupt row to a catch-all matching EVERY number under its tech
     * prefix — the opposite of the failure we are fixing, and a silent over-bill.
     */
    @Test
    void null_prefix_is_not_promoted_to_a_catch_all() {
        assertNull(TupleRateLoader.SanitizePrefix(null, "test"));
        assertNull(match(cacheWith(null, ""), "01761625306"),
                "a null-prefix rate must stay unmatchable, not become a catch-all");
    }
}
