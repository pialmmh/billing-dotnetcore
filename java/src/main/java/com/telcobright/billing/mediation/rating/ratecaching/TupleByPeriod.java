// Ported VERBATIM from legacy RateDictionary.cs (TupleByPeriod) — the per-tuple-per-day key inside the
// RateCache (IdAssignmentTuple + DRange), with its EqualityComparer for dictionary lookup.
package com.telcobright.billing.mediation.rating.ratecaching;

import java.util.Objects;

public class TupleByPeriod {
    public Integer IdAssignmentTuple;
    public DateRange DRange;
    public int Priority;

    @Override
    public String toString() {
        return (this.IdAssignmentTuple == null ? "" : this.IdAssignmentTuple.toString()) + "/" + this.DRange.toString();
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = hash * 29 + this.DRange.hashCode();
        hash = hash * 29 + Objects.hashCode(this.IdAssignmentTuple);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        return equals(obj instanceof TupleByPeriod ? (TupleByPeriod) obj : null);
    }

    public boolean equals(TupleByPeriod obj) {
        return obj != null && Objects.equals(obj.IdAssignmentTuple, this.IdAssignmentTuple) && obj.DRange.equals(this.DRange);
    }

    // Mirrors the legacy IEqualityComparer<TupleByPeriod>. Retained for fidelity; Java's HashMap uses the
    // key's own equals()/hashCode() (identical logic), so this is not wired into the loader map directly.
    public static class EqualityComparer {
        public boolean equals(TupleByPeriod x, TupleByPeriod y) {
            return x != null && y != null && x.DRange.equals(y.DRange) && Objects.equals(x.IdAssignmentTuple, y.IdAssignmentTuple);
        }

        public int getHashCode(TupleByPeriod x) {
            int hash = 17;
            hash = hash * 29 + x.DRange.hashCode();
            hash = hash * 29 + Objects.hashCode(x.IdAssignmentTuple);
            return hash;
        }
    }
}
