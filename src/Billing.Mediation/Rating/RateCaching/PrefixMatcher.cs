// Faithful port of legacy PrefixMatcher (MatchPrefixNonParallel), reading from the per-day RateCache.
// For each of the call's tuples in PRIORITY order, take that tuple's prefix dictionary for the day
// (RateCache.GetRateDictsByDay(tup.DRange)[tup]) and longest-prefix the dialed number; the first rate
// valid for the call (Category/SubCategory + answerTime within [startdate, enddate)) wins — rates are
// pre-sorted desc by start date so the latest valid one is returned. (The DateRangeWiseRateDic already
// caches per day, so the legacy PriorityAndTupleWisePrefixDicWithAssignmentTuples 2nd-level cache is
// unnecessary; rateassign stands in for Rateext, startdate/enddate for P_Startdate/P_Enddate.)
#nullable disable
using System;
using System.Collections.Generic;
using System.Linq;
using MediationModel;

namespace TelcobrightMediation
{
    public class PrefixMatcher
    {
        private static readonly DateTime MaxDate = new DateTime(9999, 12, 31, 23, 59, 59);

        private readonly List<Dictionary<string, List<rateassign>>> _priorityWisePrefixDicts =
            new List<Dictionary<string, List<rateassign>>>();
        private readonly int _category;
        private readonly int _subCategory;
        private readonly DateTime _answerTime;
        private readonly string[] _phoneNumbersAsArray;

        public PrefixMatcher(RateCache rateCache, string phoneNumber, int category, int subCategory,
            List<TupleByPeriod> tups, DateTime answerTime)
        {
            this._category = category;
            this._subCategory = subCategory;
            this._answerTime = answerTime;

            foreach (TupleByPeriod tup in tups.OrderBy(c => c.Priority))
            {
                var todaysDict = rateCache.GetRateDictsByDay(tup.DRange);
                if (todaysDict != null && todaysDict.TryGetValue(tup, out var prefixDic) && prefixDic != null)
                    this._priorityWisePrefixDicts.Add(prefixDic);
            }

            // all prefixes of the dialed number, longest first (whole number → 1 digit).
            var phChars = phoneNumber.ToCharArray();
            this._phoneNumbersAsArray = new string[phoneNumber.Length];
            for (int i = 0; i < phChars.Length; i++)
                this._phoneNumbersAsArray[i] = new string(phChars, 0, phChars.Length - i);
        }

        public rateassign MatchPrefix()
        {
            foreach (var prefixDic in this._priorityWisePrefixDicts)
            {
                foreach (string prefix in this._phoneNumbersAsArray)
                {
                    if (!prefixDic.TryGetValue(prefix, out var lstRates)) continue;
                    foreach (rateassign thisRate in lstRates)
                    {
                        if (thisRate.Category.HasValue && thisRate.Category.Value == this._category
                            && thisRate.SubCategory.HasValue && thisRate.SubCategory.Value == this._subCategory
                            && this._answerTime >= thisRate.startdate
                            && this._answerTime < (thisRate.enddate ?? MaxDate))
                        {
                            return thisRate; // longest prefix, first valid (latest start) wins
                        }
                    }
                }
            }
            return null;
        }
    }
}
