package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves which rate-plan-assignment tuples apply to a call — the faithful port of legacy
 * {@code A2ZRater.GetAssignmentTuples / GetRouteTuple / GetPartnerTuple}: try ROUTE scope
 * ({@code idService/dir/route}) first, then PARTNER scope ({@code idService/dir/idpartner}), then NULL.
 * There is NO tenant-default fallback (the legacy returned null when neither matched). The matching tuples
 * are returned PRIORITY-ordered (a partner can own several), and {@code PrefixMatcher} longest-prefixes over
 * their rates.
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
                route.computeIfAbsent(RouteKey(t.idService, t.AssignDirection, t.route), k -> new ArrayList<>()).add(t);
            else if (t.idpartner != null && t.idpartner > 0)
                partner.computeIfAbsent(PartnerKey(t.idService, t.AssignDirection, t.idpartner), k -> new ArrayList<>()).add(t);
            // a tuple with neither route nor partner is not resolvable by route/partner -> not indexed (legacy null).
        }

        return new RatePlanResolver(Freeze(route), Freeze(partner));
    }

    /**
     * The tuples that apply to the call: ROUTE scope, then PARTNER scope; priority-ordered; empty if neither
     * matches (legacy null). PrefixMatcher then longest-prefixes over their rates.
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

    private static Map<String, List<rateplanassignmenttuple>> Freeze(Map<String, List<rateplanassignmenttuple>> index) {
        var frozen = new HashMap<String, List<rateplanassignmenttuple>>(index.size());
        for (var e : index.entrySet())
            frozen.put(e.getKey(), e.getValue().stream()
                    .sorted(Comparator.comparingInt((rateplanassignmenttuple t) -> t.priority)).collect(Collectors.toList())); // lowest priority first
        return frozen;
    }

    public static final RatePlanResolver Empty = Build(List.of());
}
