// Ported VERBATIM from legacy RateDictionary.cs (TupleByPeriod) — the per-tuple-per-day key inside the
// RateCache (IdAssignmentTuple + DRange), with its EqualityComparer for dictionary lookup.
#nullable disable
using System;
using System.Collections.Generic;
using LibraryExtensions;

namespace TelcobrightMediation
{
    public class TupleByPeriod : IEquatable<TupleByPeriod>
    {
        public int? IdAssignmentTuple { get; set; }
        public DateRange DRange { get; set; }
        public int Priority { get; set; }

        public override string ToString() => this.IdAssignmentTuple.ToString() + "/" + this.DRange.ToString();

        public override int GetHashCode()
        {
            unchecked
            {
                int hash = 17;
                hash = hash * 29 + this.DRange.GetHashCode();
                hash = hash * 29 + this.IdAssignmentTuple.GetHashCode();
                return hash;
            }
        }

        public override bool Equals(object obj) => Equals(obj as TupleByPeriod);

        public bool Equals(TupleByPeriod obj) =>
            obj != null && obj.IdAssignmentTuple == this.IdAssignmentTuple && obj.DRange.Equals(this.DRange);

        public class EqualityComparer : IEqualityComparer<TupleByPeriod>
        {
            public bool Equals(TupleByPeriod x, TupleByPeriod y) =>
                x != null && y != null && x.DRange.Equals(y.DRange) && x.IdAssignmentTuple == y.IdAssignmentTuple;

            public int GetHashCode(TupleByPeriod x)
            {
                unchecked
                {
                    int hash = 17;
                    hash = hash * 29 + x.DRange.GetHashCode();
                    hash = hash * 29 + x.IdAssignmentTuple.GetHashCode();
                    return hash;
                }
            }
        }
    }
}
