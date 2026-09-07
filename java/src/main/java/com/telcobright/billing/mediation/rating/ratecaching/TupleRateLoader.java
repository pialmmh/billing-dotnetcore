// The CONFIG-FED IRateLoader — the faithful port of the legacy RateCache BUILD (PopulateDicByDay ->
// RateDictionaryGeneratorByTuples -> RateList): the JOIN tuple -> rateassign -> rateplan -> rate, done in
// memory instead of via SQL. config-manager serves the tenant's rateplanassignmenttuples (each carrying its
// nested rateassigns = the legacy rateassign JOIN rows), the actual rate rows per rate plan, and the rate
// plans; this loader projects them into one day's DateRangeWiseRateDic[day] shape
// (TupleByPeriod -> prefix -> List<Rateext>) exactly as the legacy did.
//
// Per tuple, per join rateassign (legacy RateList.GetRatePlanByAssignmentTuple + GetRatesByRatePlan):
//   idRatePlan   = rateassign.Inactive               (legacy: "inactive is the idrateplan in rateassign")
//   assignSpan   = [rateassign.startdate, rateassign.enddate || 9999-12-31 23:59:59]
//   intersection = Util.DateIntersection(day, assignSpan); skip the rateassign if it does not intersect
//   openAssignment = rateassign.enddate == null ? 1 : 0
//   keep each rate r in rateRowsByRatePlan[idRatePlan] whose validity overlaps the INTERSECTED span (the
//     legacy temp_rate / GetWhereExpressionRates predicate), and build a Rateext copying r's fields and
//     stamping the rate-plan-assignment overlay (AssignmentFlag/Startdatebyrateplan/Enddatebyrateplan/
//     OpenRateAssignment/Priority/TechPrefix/IdPartner/IdRoute/IdRatePlanAssignmentTuple).
// Then RateListToDictionary: group the tuple's Rateext by key = TechPrefix + Prefix, each list sorted by
// P_Startdate() DESC (so PrefixMatcher's "last valid in the list wins" returns the earliest-start match).
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;

import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TupleRateLoader implements IRateLoader {
    private static final LocalDateTime MaxDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
    private static final Logger LOG = Logger.getLogger(TupleRateLoader.class);

    private final List<rateplanassignmenttuple> _tuples;
    private final RateRowsByDateProvider _rowsProvider;
    private final Map<String, rateplan> _dicRatePlan;

    /** Legacy/test path: a single fixed rate-row snapshot used for every day (today-only behaviour). */
    public TupleRateLoader(List<rateplanassignmenttuple> tuples,
            Map<Integer, List<rate>> rateRowsByRatePlan,
            Map<String, rateplan> dicRatePlan) {
        this(tuples, RateRowsByDateProvider.ofFixed(rateRowsByRatePlan), dicRatePlan);
    }

    /** Config-fed path: the rate rows are resolved PER DAY (snapshot for today/tomorrow, fetched for back-dates). */
    public TupleRateLoader(List<rateplanassignmenttuple> tuples,
            RateRowsByDateProvider rowsProvider,
            Map<String, rateplan> dicRatePlan) {
        _tuples = tuples != null ? tuples : new ArrayList<>();
        _rowsProvider = rowsProvider != null ? rowsProvider : RateRowsByDateProvider.ofFixed(null);
        _dicRatePlan = dicRatePlan != null ? dicRatePlan : new HashMap<>();
    }

    @Override
    public Map<TupleByPeriod, Map<String, List<Rateext>>> LoadDay(DateRange dRange) {
        // (Legacy keyed dicByDay with a TupleByPeriod.EqualityComparer; Java's HashMap uses TupleByPeriod's
        //  own equals()/hashCode() — the same (IdAssignmentTuple, DRange) value-equality.)
        var result = new HashMap<TupleByPeriod, Map<String, List<Rateext>>>();

        // Rate rows valid on THIS day: today/tomorrow from the pushed snapshot, older days fetched on demand.
        var rateRowsForDay = _rowsProvider.rowsForDate(dRange.StartDate.toLocalDate());

        for (var tuple : _tuples) {
            // accumulate ALL the tuple's day-valid Rateext across its rateassigns (legacy RateList.GetAllRates).
            var tupleRates = new ArrayList<Rateext>();
            var rateassigns = tuple.rateassigns != null ? tuple.rateassigns : new ArrayList<rateassign>();
            for (var ra : rateassigns) {
                int idRatePlan = ra.Inactive;                              // quirk: Inactive holds the idrateplan
                var assignStart = ra.startdate;
                var assignEnd = ra.enddate != null ? ra.enddate : MaxDate; // open assignment -> max future
                var intersection = DateIntersection(dRange, new DateRange(assignStart, assignEnd));
                if (intersection == null) continue;                       // assignment does not intersect the day

                int openAssignment = ra.enddate == null ? 1 : 0;
                var techPrefix = TechPrefixFor(idRatePlan);
                var rateRows = rateRowsForDay.get(idRatePlan);
                if (rateRows == null) continue;

                for (var r : rateRows) {
                    if (!OverlapsSpan(r, intersection)) continue;         // legacy temp_rate overlap predicate
                    Rateext e = toRateext(r);
                    e.AssignmentFlag = 1;
                    e.Startdatebyrateplan = ra.startdate;
                    e.Enddatebyrateplan = ra.enddate;
                    e.OpenRateAssignment = openAssignment;
                    e.Priority = tuple.priority;
                    e.TechPrefix = techPrefix;
                    e.IdPartner = tuple.idpartner != null ? tuple.idpartner : -1;
                    e.IdRoute = tuple.route != null ? tuple.route : -1;
                    e.IdRatePlanAssignmentTuple = tuple.id;
                    tupleRates.add(e);
                }
            }

            var byPrefix = RateListToDictionary(tupleRates);

            var key = new TupleByPeriod();
            key.IdAssignmentTuple = tuple.id;
            key.DRange = dRange;
            key.Priority = tuple.priority;
            result.put(key, byPrefix);
        }
        return result;
    }

    // legacy RateListToDictionary: bucket by techPrefix + Prefix; within a bucket sort by P_Startdate() DESC
    // (the latest-start rate first), so PrefixMatcher's "keep the last valid in the list" yields the earliest
    // valid start that still covers the call.
    private static Map<String, List<Rateext>> RateListToDictionary(List<Rateext> rates) {
        var byPrefix = new HashMap<String, List<Rateext>>();
        for (var r : rates) {
            String techPrefix = r.TechPrefix != null ? r.TechPrefix : "";
            String key = techPrefix + r.Prefix;
            byPrefix.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        // P_Startdate() can be null (a lifted null from the C# getter); push nulls last, otherwise desc.
        var byStartDesc = Comparator
                .comparing((Rateext x) -> x.P_Startdate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
        for (var list : byPrefix.values())
            list.sort(byStartDesc);
        return byPrefix;
    }

    private String TechPrefixFor(int idRatePlan) {
        rateplan rp = _dicRatePlan.get(Integer.toString(idRatePlan));
        if (rp == null || rp.field4 == null) return "";
        return SanitizePrefix(rp.field4, "rateplan " + idRatePlan + " field4 (techPrefix)");
    }

    /**
     * Scrub a prefix before it becomes half of a RateCache key. Rate prefixes are dialable digits (plus a
     * leading {@code +}); a control character or stray whitespace in one is corrupt CONFIG, and because the
     * cache is keyed on {@code techPrefix + Prefix} such a value can never be longest-prefix matched by any
     * dialed number — the rate is simply invisible and every call it should have covered dies as
     * {@code RATE_NOT_FOUND}, with the SELECT output looking perfectly fine (the char is unprintable).
     *
     * <p>That is not hypothetical: on 2026-08-06 all four {@code res_261.rate} rows were seeded on the master
     * with a trailing {@code 0x1F} ({@code '880'} stored as {@code 3838301F}), which silently stopped the whole
     * reseller tier from rating once config-manager was repointed at that master on 2026-09-02. Rather than
     * zero-match in silence we strip the junk so the rate still applies, and WARN once per distinct bad value
     * so the underlying data defect is visible and gets fixed at its source.
     */
    static String SanitizePrefix(String prefix, String what) {
        // null is passed through UNCHANGED on purpose: a null Prefix already keys as the literal "…null" and is
        // unmatchable, and turning it into "" would promote a corrupt row into a catch-all that matches every
        // number under its tech prefix. Sanitizing must never make a rate match MORE than it did.
        if (prefix == null || prefix.isEmpty()) return prefix;
        boolean dirty = false;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) { dirty = true; break; }
        }
        if (!dirty) return prefix;                                  // the overwhelmingly common path: no copy

        var clean = new StringBuilder(prefix.length());
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!Character.isISOControl(c) && !Character.isWhitespace(c)) clean.append(c);
        }
        String result = clean.toString();
        WarnOnce(what, prefix, result);
        return result;
    }

    // One WARN per distinct (what, raw) pair — this runs per tenant per day-boundary reload, so an unbounded
    // or un-deduped log would either spam or leak. Bounded: past the cap we stop remembering (and stop
    // warning), which is fine — a config defect this widespread has already been reported many times over.
    private static final int MaxWarnedPrefixes = 256;
    private static final Set<String> WarnedPrefixes = ConcurrentHashMap.newKeySet();

    private static void WarnOnce(String what, String raw, String cleaned) {
        String seenKey = what + ' ' + raw;
        if (WarnedPrefixes.size() >= MaxWarnedPrefixes || !WarnedPrefixes.add(seenKey)) return;
        LOG.warnf("CORRUPT RATE PREFIX: %s = %s -> using '%s'. Fix the source data; until then this rate is "
                + "matched on the cleaned value.", what, Escape(raw), cleaned);
    }

    /** Render a prefix with its unprintables visible (a plain log line would hide the very char at fault). */
    private static String Escape(String s) {
        var sb = new StringBuilder(s.length() + 8).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
            else sb.append(String.format("\\x%02X", (int) c));
        }
        return sb.append('\'').toString();
    }

    // legacy DateRange.GetWhereExpressionRates(startdate, enddate) over the intersected span:
    //   (r.startdate <= spanStart && (r.enddate||max) > spanStart) || (r.startdate >= spanStart && r.startdate < spanEnd)
    private static boolean OverlapsSpan(rate r, DateRange span) {
        var spanStart = span.StartDate;
        var spanEnd = span.EndDate;
        var end = r.enddate != null ? r.enddate : MaxDate;
        return (!r.startdate.isAfter(spanStart) && end.isAfter(spanStart))
               || (!r.startdate.isBefore(spanStart) && r.startdate.isBefore(spanEnd));
    }

    // Ported VERBATIM from legacy LibraryExtensions.Util.DateIntersection — returns null when the ranges do
    // not intersect, else the overlapping [start, end].
    static DateRange DateIntersection(DateRange compareMe, DateRange compareWith) {
        DateRange intersection = null;
        if (!compareMe.StartDate.isAfter(compareWith.StartDate) && compareMe.EndDate.isAfter(compareWith.StartDate)) {
            intersection = new DateRange();
            intersection.StartDate = compareWith.StartDate;
            if (compareMe.EndDate.isAfter(compareWith.EndDate)) intersection.EndDate = compareWith.EndDate;
            else intersection.EndDate = compareMe.EndDate;                 // compareMe.EndDate <= compareWith.EndDate
            return intersection;
        } else if (!compareMe.StartDate.isBefore(compareWith.StartDate) && compareMe.StartDate.isBefore(compareWith.EndDate)) {
            intersection = new DateRange();
            intersection.StartDate = compareMe.StartDate;
            if (!compareMe.EndDate.isBefore(compareWith.EndDate)) intersection.EndDate = compareWith.EndDate;
            else intersection.EndDate = compareMe.EndDate;                 // compareMe.EndDate < compareWith.EndDate
        }
        return intersection;                                              // null if no intersection
    }

    // legacy `select r.*` into Rateext — copy every base rate column (the overlay fields are stamped by the
    // caller). Keeps the loader the only place that constructs a matched Rateext from a rate row.
    private static Rateext toRateext(rate r) {
        Rateext e = new Rateext();
        e.id = r.id;
        e.ProductId = r.ProductId;
        // Scrubbed HERE, at the one place a rate row enters the cache, so the cleaned value is what gets keyed
        // AND what flows downstream into acc_chargeable.Prefix / cdr.MatchedPrefixCustomer.
        e.Prefix = SanitizePrefix(r.Prefix, "rate " + r.id + " Prefix");
        e.description = r.description;
        e.rateamount = r.rateamount;
        e.WeekDayStart = r.WeekDayStart;
        e.WeekDayEnd = r.WeekDayEnd;
        e.starttime = r.starttime;
        e.endtime = r.endtime;
        e.Resolution = r.Resolution;
        e.MinDurationSec = r.MinDurationSec;
        e.SurchargeTime = r.SurchargeTime;
        e.SurchargeAmount = r.SurchargeAmount;
        e.idrateplan = r.idrateplan;
        e.CountryCode = r.CountryCode;
        e.date1 = r.date1;
        e.field1 = r.field1;
        e.field2 = r.field2;
        e.field3 = r.field3;
        e.field4 = r.field4;
        e.field5 = r.field5;
        e.startdate = r.startdate;
        e.enddate = r.enddate;
        e.Inactive = r.Inactive;
        e.RouteDisabled = r.RouteDisabled;
        e.Type = r.Type;
        e.Currency = r.Currency;
        e.OtherAmount1 = r.OtherAmount1;
        e.OtherAmount2 = r.OtherAmount2;
        e.OtherAmount3 = r.OtherAmount3;
        e.OtherAmount4 = r.OtherAmount4;
        e.OtherAmount5 = r.OtherAmount5;
        e.OtherAmount6 = r.OtherAmount6;
        e.OtherAmount7 = r.OtherAmount7;
        e.OtherAmount8 = r.OtherAmount8;
        e.OtherAmount9 = r.OtherAmount9;
        e.OtherAmount10 = r.OtherAmount10;
        e.TimeZoneOffsetSec = r.TimeZoneOffsetSec;
        e.RatePosition = r.RatePosition;
        e.IgwPercentageIn = r.IgwPercentageIn;
        e.ConflictingRateIds = r.ConflictingRateIds;
        e.ChangedByTaskId = r.ChangedByTaskId;
        e.ChangedOn = r.ChangedOn;
        e.Status = r.Status;
        e.idPreviousRate = r.idPreviousRate;
        e.EndPreviousRate = r.EndPreviousRate;
        e.Category = r.Category;
        e.SubCategory = r.SubCategory;
        e.ChangeCommitted = r.ChangeCommitted;
        e.ConflictingRates = r.ConflictingRates;
        e.OverlappingRates = r.OverlappingRates;
        e.Comment1 = r.Comment1;
        e.Comment2 = r.Comment2;
        e.billingspan = r.billingspan;
        e.RateAmountRoundupDecimal = r.RateAmountRoundupDecimal;
        return e;
    }
}
