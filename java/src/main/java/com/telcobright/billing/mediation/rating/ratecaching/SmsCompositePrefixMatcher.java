package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.sms.SmsCompositePrefix;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The OUTGOING-SMS rate matcher: CALLING (sender/mask) + CALLED, over the same per-day {@link RateCache} the
 * voice {@link PrefixMatcher} reads. It never replaces {@link PrefixMatcher}; voice keeps matching on the called
 * number alone.
 *
 * <p><b>Prefix semantics</b> are routesphere's {@code RatePrefixMatcher} (the matcher that already charges the
 * SMS balance): {@code rate.Prefix = <callingPrefix>0x1F<calledPrefix>}, read from {@link Rateext#RawPrefix}
 * (the sanitized {@code Prefix} has the delimiter stripped). A rate matches when each NON-empty side is a
 * case-sensitive {@code startsWith} of its number; a prefix with no 0x1F is destination-only, so legacy rows
 * keep working as fallbacks. Most specific wins: largest combined length, then the longer calling side, then
 * the lexicographically smaller raw key — deterministic regardless of map order.</p>
 *
 * <p><b>Tech prefix ({@code rateplan.field4}) is ignored</b>, exactly as routesphere ignores it (it keys rates
 * by the raw {@code rate.Prefix}). Voice keeps its tech-prefix behaviour; only this matcher drops it.</p>
 *
 * <p><b>Everything else is the legacy {@link PrefixMatcher} frame</b>: tuples are tried in ascending priority and
 * the first priority with a match wins; a rate is valid only for the matching category/subcategory and when
 * the answer time is inside its effective period; among same-prefix rows (sorted by effective start DESC) the
 * LAST valid one is kept — the earliest start that still covers the SMS.</p>
 */
public final class SmsCompositePrefixMatcher {
    private static final LocalDateTime MaxDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final List<Map<String, List<Rateext>>> _priorityWisePrefixDicts = new ArrayList<>();
    private final String _calling;
    private final String _called;
    private final int _category;
    private final int _subCategory;
    private final LocalDateTime _answerTime;
    private final IntPredicate _tupleFilter;

    /**
     * @param tupleFilter which assignment tuples may supply a rate (the legacy billing-rule SG gate); a rate from
     *                    any other tuple is never a candidate.
     */
    public SmsCompositePrefixMatcher(RateCache rateCache, String calling, String called, int category,
            int subCategory, List<TupleByPeriod> tups, LocalDateTime answerTime, IntPredicate tupleFilter) {
        _calling = calling == null ? "" : calling;
        _called = called == null ? "" : called;
        _category = category;
        _subCategory = subCategory;
        _answerTime = answerTime;
        _tupleFilter = tupleFilter != null ? tupleFilter : t -> true;
        for (TupleByPeriod tup : tups.stream().sorted(Comparator.comparingInt((TupleByPeriod c) -> c.Priority)).toList()) {
            if (!_tupleFilter.test(tup.IdAssignmentTuple)) continue;
            var todaysDict = rateCache.GetRateDictsByDay(tup.DRange);
            if (todaysDict == null) continue;
            var prefixDic = todaysDict.get(tup);
            if (prefixDic != null) _priorityWisePrefixDicts.add(prefixDic);
        }
    }

    public Rateext Match() {
        for (Map<String, List<Rateext>> prefixDic : _priorityWisePrefixDicts) {
            Rateext best = null;
            SmsCompositePrefix bestKey = null;
            for (var entry : ValidRateByRawPrefix(prefixDic).entrySet()) {
                SmsCompositePrefix key = SmsCompositePrefix.Parse(entry.getKey());
                if (!key.Matches(_calling, _called)) continue;
                if (bestKey == null || IsMoreSpecific(key, bestKey)) {
                    best = entry.getValue();
                    bestKey = key;
                }
            }
            if (best != null) return best;      // this priority matched; lower priorities are not consulted
        }
        return null;
    }

    /**
     * One candidate per RAW prefix: the voice dictionary is keyed by the SANITIZED prefix (0x1F stripped), so the
     * rows are regrouped by their raw value first, then each group keeps its last valid row in start-DESC order.
     */
    private Map<String, Rateext> ValidRateByRawPrefix(Map<String, List<Rateext>> prefixDic) {
        var byRaw = new HashMap<String, List<Rateext>>();
        for (List<Rateext> bucket : prefixDic.values())
            for (Rateext r : bucket) {
                String raw = r.RawPrefix != null ? r.RawPrefix : r.Prefix;
                if (raw == null) continue;      // a null prefix is unmatchable (never promoted to a catch-all)
                byRaw.computeIfAbsent(raw, k -> new ArrayList<>()).add(r);
            }
        var byStartDesc = Comparator
                .comparing((Rateext x) -> x.P_Startdate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
        var chosen = new HashMap<String, Rateext>();
        for (var e : byRaw.entrySet()) {
            List<Rateext> list = e.getValue();
            list.sort(byStartDesc);
            Rateext lastValid = null;
            for (Rateext r : list) if (IsValid(r)) lastValid = r;     // keep the LAST valid (legacy overwrite)
            if (lastValid != null) chosen.put(e.getKey(), lastValid);
        }
        return chosen;
    }

    /** Routesphere tie-break: larger total, then longer calling side, then lexicographically smaller raw key. */
    static boolean IsMoreSpecific(SmsCompositePrefix candidate, SmsCompositePrefix best) {
        if (candidate.TotalLength() != best.TotalLength()) return candidate.TotalLength() > best.TotalLength();
        if (candidate.CallingPrefix().length() != best.CallingPrefix().length())
            return candidate.CallingPrefix().length() > best.CallingPrefix().length();
        return candidate.Raw().compareTo(best.Raw()) < 0;
    }

    // Same validity predicate as PrefixMatcher (C# lifted comparisons: a null Category/SubCategory/start is invalid).
    private boolean IsValid(Rateext r) {
        LocalDateTime pStart = r.P_Startdate();
        LocalDateTime pEnd = r.P_Enddate();
        return r.Category != null && r.Category == _category
                && r.SubCategory != null && r.SubCategory == _subCategory
                && pStart != null && !_answerTime.isBefore(pStart)
                && _answerTime.isBefore(pEnd != null ? pEnd : MaxDate);
    }
}
