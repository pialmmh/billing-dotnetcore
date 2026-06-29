// Faithful port of legacy PrefixMatcher.MatchPrefixParallel (the runtime path), reading the per-day RateCache.
// For each resolved tuple's prefix-dict in PRIORITY order:
//   - build the dialed number's prefixes longest-first (whole number -> 1 digit);
//   - for each prefix, scan that prefix's rate list and KEEP THE LAST valid rate (do NOT break) — the legacy
//     Parallel.For overwrites prefixWiseMatchedRates[i], so the last valid wins; since each list is sorted
//     DESC by P_Startdate(), the last valid = the EARLIEST-start rate that still covers the call;
//   - the LONGEST prefix that yielded a match wins;
//   - if a priority tuple yields no match, fall through to the next priority; null if none match.
// valid = Category==category && SubCategory==subCategory && answerTime >= P_Startdate()
//         && answerTime < (P_Enddate()!=null ? P_Enddate() : 9999-12-31 23:59:59).
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PrefixMatcher {
    private static final LocalDateTime MaxDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final List<Map<String, List<Rateext>>> _priorityWisePrefixDicts = new ArrayList<>();
    private final int _category;
    private final int _subCategory;
    private final LocalDateTime _answerTime;
    private final String[] _phoneNumbersAsArray;

    public PrefixMatcher(RateCache rateCache, String phoneNumber, int category, int subCategory,
            List<TupleByPeriod> tups, LocalDateTime answerTime) {
        this._category = category;
        this._subCategory = subCategory;
        this._answerTime = answerTime;

        // TupleByPeriod = one rateplanassignmenttuple on the day of answertime; process by ascending priority.
        for (TupleByPeriod tup : tups.stream().sorted(Comparator.comparingInt((TupleByPeriod c) -> c.Priority)).toList()) {
            var todaysDict = rateCache.GetRateDictsByDay(tup.DRange);
            if (todaysDict != null) {
                var prefixDic = todaysDict.get(tup);
                if (prefixDic != null)
                    this._priorityWisePrefixDicts.add(prefixDic);
            }
        }

        // all prefixes of the dialed number, longest first (whole number -> 1 digit).
        var phChars = phoneNumber.toCharArray();
        this._phoneNumbersAsArray = new String[phoneNumber.length()];
        for (int i = 0; i < phChars.length; i++)
            this._phoneNumbersAsArray[i] = new String(phChars, 0, phChars.length - i);
    }

    public Rateext MatchPrefix() {
        for (Map<String, List<Rateext>> prefixDic : this._priorityWisePrefixDicts) {
            Rateext[] prefixWiseMatchedRates = new Rateext[this._phoneNumbersAsArray.length];
            boolean matchFound = false;
            for (int i = 0; i < this._phoneNumbersAsArray.length; i++) {
                var lstRates = prefixDic.get(this._phoneNumbersAsArray[i]);
                if (lstRates == null) continue;
                for (Rateext thisRate : lstRates) {
                    if (IsValid(thisRate)) {
                        prefixWiseMatchedRates[i] = thisRate;   // keep the LAST valid (legacy overwrite)
                        matchFound = true;
                    }
                }
            }
            if (!matchFound) continue;                          // this priority had no match -> next priority
            for (Rateext t : prefixWiseMatchedRates)            // longest prefix first
                if (t != null) return t;
        }
        return null;
    }

    // C# lifted comparisons: a null Category/SubCategory/P_Startdate() makes the predicate false.
    private boolean IsValid(Rateext r) {
        LocalDateTime pStart = r.P_Startdate();
        LocalDateTime pEnd = r.P_Enddate();
        return r.Category != null && r.Category == this._category
                && r.SubCategory != null && r.SubCategory == this._subCategory
                && pStart != null && !this._answerTime.isBefore(pStart)
                && this._answerTime.isBefore(pEnd != null ? pEnd : MaxDate);
    }
}
