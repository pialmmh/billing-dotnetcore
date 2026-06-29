package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves which rate-plan-assignment tuples apply to a call — the lean port of legacy
 * {@code A2ZRater.GetAssignmentTuples}. Tuples are indexed by the legacy {@code GetTuple()} key forms
 * ({@code idService/dir/route} and {@code idService/dir/idpartner}); resolution tries the route scope
 * first, then the partner scope, returning the matching tuples priority-ordered for
 * {@code PrefixMatcher} to longest-prefix over their {@code rateassigns}.
 */
public final class RatePlanResolver {
    private final Map<String, List<rateplanassignmenttuple>> _routeIndex;
    private final Map<String, List<rateplanassignmenttuple>> _partnerIndex;

    private RatePlanResolver(
            Map<String, List<rateplanassignmenttuple>> routeIndex,
            Map<String, List<rateplanassignmenttuple>> partnerIndex) {
        this._routeIndex = routeIndex;
        this._partnerIndex = partnerIndex;
    }

    public static RatePlanResolver Build(List<rateplanassignmenttuple> tuples) {
        var route = new HashMap<String, List<rateplanassignmenttuple>>();
        var partner = new HashMap<String, List<rateplanassignmenttuple>>();

        for (var t : tuples) {
            if (t.route != null && t.route > 0)
                Add(route, RouteKey(t.idService, t.AssignDirection, t.route), t);
            else if (t.idpartner != null && t.idpartner > 0)
                Add(partner, PartnerKey(t.idService, t.AssignDirection, t.idpartner), t);
        }

        return new RatePlanResolver(Freeze(route), Freeze(partner));
    }

    /**
     * The tuples that apply to the call (route scope preferred over partner scope),
     * priority-ordered; empty if none. PrefixMatcher then longest-prefixes over their rateassigns.
     */
    public List<rateplanassignmenttuple> Resolve(int idService, int assignDirection, Integer idPartner, Integer route) {
        if (route != null && route > 0) {
            var rt = _routeIndex.get(RouteKey(idService, assignDirection, route));
            if (rt != null) return rt;
        }

        if (idPartner != null && idPartner > 0) {
            var pt = _partnerIndex.get(PartnerKey(idService, assignDirection, idPartner));
            if (pt != null) return pt;
        }

        return List.of();
    }

    private static String RouteKey(int idService, int dir, int route) {
        return idService + "/" + dir + "/" + route;
    }

    private static String PartnerKey(int idService, int dir, int idPartner) {
        return idService + "/" + dir + "/" + idPartner;
    }

    private static void Add(Map<String, List<rateplanassignmenttuple>> index, String key, rateplanassignmenttuple t) {
        var list = index.get(key);
        if (list == null) {
            list = new ArrayList<>();
            index.put(key, list);
        }
        list.add(t);
    }

    private static Map<String, List<rateplanassignmenttuple>> Freeze(
            Map<String, List<rateplanassignmenttuple>> index) {
        var frozen = new HashMap<String, List<rateplanassignmenttuple>>(index.size());
        for (var entry : index.entrySet())
            frozen.put(entry.getKey(),
                    entry.getValue().stream().sorted(Comparator.comparingInt((rateplanassignmenttuple t) -> t.priority)).collect(Collectors.toList()));   // lowest priority first
        return frozen;
    }

    public static final RatePlanResolver Empty = Build(List.of());
}
