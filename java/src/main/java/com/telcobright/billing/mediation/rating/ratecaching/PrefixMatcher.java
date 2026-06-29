// Faithful port of legacy PrefixMatcher (MatchPrefixNonParallel), reading from the per-day RateCache.
// For each of the call's tuples in PRIORITY order, take that tuple's prefix dictionary for the day
// (RateCache.GetRateDictsByDay(tup.DRange)[tup]) and longest-prefix the dialed number; the first rate
// valid for the call (Category/SubCategory + answerTime within [startdate, enddate)) wins — rates are
// pre-sorted desc by start date so the latest valid one is returned. (The DateRangeWiseRateDic already
// caches per day, so the legacy PriorityAndTupleWisePrefixDicWithAssignmentTuples 2nd-level cache is
// unnecessary; rateassign stands in for Rateext, startdate/enddate for P_Startdate/P_Enddate.)
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PrefixMatcher {
    private static final LocalDateTime MaxDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final List<Map<String, List<rateassign>>> _priorityWisePrefixDicts =
        new ArrayList<>();
    private final int _category;
    private final int _subCategory;
    private final LocalDateTime _answerTime;
    private final String[] _phoneNumbersAsArray;

    public PrefixMatcher(RateCache rateCache, String phoneNumber, int category, int subCategory,
            List<TupleByPeriod> tups, LocalDateTime answerTime) {
        this._category = category;
        this._subCategory = subCategory;
        this._answerTime = answerTime;

        for (TupleByPeriod tup : tups.stream().sorted(Comparator.comparingInt((TupleByPeriod c) -> c.Priority)).toList()) {
            var todaysDict = rateCache.GetRateDictsByDay(tup.DRange);
            if (todaysDict != null) {
                var prefixDic = todaysDict.get(tup);
                if (prefixDic != null)
                    this._priorityWisePrefixDicts.add(prefixDic);
            }
        }

        // all prefixes of the dialed number, longest first (whole number → 1 digit).
        var phChars = phoneNumber.toCharArray();
        this._phoneNumbersAsArray = new String[phoneNumber.length()];
        for (int i = 0; i < phChars.length; i++)
            this._phoneNumbersAsArray[i] = new String(phChars, 0, phChars.length - i);
    }

    public rateassign MatchPrefix() {
        for (Map<String, List<rateassign>> prefixDic : this._priorityWisePrefixDicts) {
            for (String prefix : this._phoneNumbersAsArray) {
                var lstRates = prefixDic.get(prefix);
                if (lstRates == null) continue;
                for (rateassign thisRate : lstRates) {
                    if (thisRate.Category != null && thisRate.Category == this._category
                        && thisRate.SubCategory != null && thisRate.SubCategory == this._subCategory
                        && !this._answerTime.isBefore(thisRate.startdate)
                        && this._answerTime.isBefore(thisRate.enddate != null ? thisRate.enddate : MaxDate)) {
                        return thisRate; // longest prefix, first valid (latest start) wins
                    }
                }
            }
        }
        return null;
    }
}
