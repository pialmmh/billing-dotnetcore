// Ported VERBATIM from legacy Models_Mediation/rateplanassignmenttuple.cs (+ its EntityExtensions
// GetTuple helpers, folded in here). EF navigation props (billingruleassignment, route1, ratetaskassigns)
// REMOVED; the rateassigns collection is KEPT as the data the rater matches over (a tuple owns its
// rates). config-manager serves this shape (tuple + nested rateassigns) so .NET deserializes 1:1.
package com.telcobright.billing.mediation.engine.models;

import java.util.ArrayList;
import java.util.List;

public class rateplanassignmenttuple {
    public rateplanassignmenttuple() {
        this.rateassigns = new ArrayList<>();
    }

    public int id;
    public int idService;
    public int AssignDirection;
    public Integer idpartner;
    public Integer route;
    public int priority;

    // C# `ICollection<rateassign> rateassigns` -> java.util.List<rateassign> (it is initialised with a
    // List and only iterated/streamed downstream).
    public List<rateassign> rateassigns;

    // --- legacy EntityExtensions: the resolution key forms (route-first, then partner, then service) ---

    private String GetServiceTupleWithoutPriority() {
        return String.valueOf(this.idService);
    }

    // NOTE: C# `Nullable<int>.ToString()` yields "" when null (NOT "null"); preserved verbatim below.
    private String GetPartnerTupleWithoutPriority() {
        return new StringBuilder(GetServiceTupleWithoutPriority()).append("/")
                .append(String.valueOf(this.AssignDirection)).append("/")
                .append(this.idpartner != null ? this.idpartner.toString() : "").toString();
    }

    private String GetRouteTupleWithoutPriority() {
        return new StringBuilder(GetServiceTupleWithoutPriority()).append("/")
                .append(String.valueOf(this.AssignDirection)).append("/")
                .append(this.route != null ? this.route.toString() : "").toString();
    }

    public String GetTuple() {
        if (this.route != null && this.route > 0)
            return GetRouteTupleWithoutPriority();
        if (this.idpartner != null && this.idpartner > 0)
            return GetPartnerTupleWithoutPriority();
        return GetServiceTupleWithoutPriority();
    }
}
