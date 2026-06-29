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
 * {@code A2ZRater.GetAssignmentTuples}. Tuples are indexed by route ({@code idService/dir/route}) then by
 * partner ({@code idService/dir/idpartner}); resolution tries route scope first, then partner scope, then
 * the tenant default — returning the matching tuples priority-ordered for {@code PrefixMatcher} to
 * longest-prefix over their {@code rateassigns}.
 *
 * <p><b>Reseller-model fallback:</b> config-manager serves a tenant's customer/supplier rates FLAT (one rate
 * plan per tier, with no per-partner/route tuples). Such a tuple — one carrying neither {@code route} nor
 * {@code idpartner} — is indexed as that direction's <i>default</i> and returned when no route/partner tuple
 * matches, so the tenant's single plan applies to any partner. (The legacy returned null here; this fallback
 * realizes the one-plan-per-tier model fed from the DynamicContext.)
 */
public final class RatePlanResolver {
    private final Map<String, List<rateplanassignmenttuple>> _routeIndex;
    private final Map<String, List<rateplanassignmenttuple>> _partnerIndex;
    private final Map<Integer, List<rateplanassignmenttuple>> _defaultByDirection;

    private RatePlanResolver(
            Map<String, List<rateplanassignmenttuple>> routeIndex,
            Map<String, List<rateplanassignmenttuple>> partnerIndex,
            Map<Integer, List<rateplanassignmenttuple>> defaultByDirection) {
        this._routeIndex = routeIndex;
        this._partnerIndex = partnerIndex;
        this._defaultByDirection = defaultByDirection;
    }

    public static RatePlanResolver Build(List<rateplanassignmenttuple> tuples) {
        var route = new HashMap<String, List<rateplanassignmenttuple>>();
        var partner = new HashMap<String, List<rateplanassignmenttuple>>();
        var byDirection = new HashMap<Integer, List<rateplanassignmenttuple>>();

        for (var t : tuples) {
            if (t.route != null && t.route > 0)
                route.computeIfAbsent(RouteKey(t.idService, t.AssignDirection, t.route), k -> new ArrayList<>()).add(t);
            else if (t.idpartner != null && t.idpartner > 0)
                partner.computeIfAbsent(PartnerKey(t.idService, t.AssignDirection, t.idpartner), k -> new ArrayList<>()).add(t);
            else
                byDirection.computeIfAbsent(t.AssignDirection, k -> new ArrayList<>()).add(t);   // tenant default
        }

        return new RatePlanResolver(FreezeStr(route), FreezeStr(partner), FreezeInt(byDirection));
    }

    /**
     * The tuples that apply to the call: route scope, then partner scope, then the tenant default for the
     * direction; priority-ordered; empty if none. PrefixMatcher then longest-prefixes over their rateassigns.
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
        var def = _defaultByDirection.get(assignDirection);   // the tenant's single plan for this direction
        if (def != null) return def;
        return List.of();
    }

    private static String RouteKey(int idService, int dir, int route) {
        return idService + "/" + dir + "/" + route;
    }

    private static String PartnerKey(int idService, int dir, int idPartner) {
        return idService + "/" + dir + "/" + idPartner;
    }

    private static Map<String, List<rateplanassignmenttuple>> FreezeStr(Map<String, List<rateplanassignmenttuple>> index) {
        var frozen = new HashMap<String, List<rateplanassignmenttuple>>(index.size());
        for (var e : index.entrySet())
            frozen.put(e.getKey(), e.getValue().stream()
                    .sorted(Comparator.comparingInt((rateplanassignmenttuple t) -> t.priority)).collect(Collectors.toList()));   // lowest priority first
        return frozen;
    }

    private static Map<Integer, List<rateplanassignmenttuple>> FreezeInt(Map<Integer, List<rateplanassignmenttuple>> index) {
        var frozen = new HashMap<Integer, List<rateplanassignmenttuple>>(index.size());
        for (var e : index.entrySet())
            frozen.put(e.getKey(), e.getValue().stream()
                    .sorted(Comparator.comparingInt((rateplanassignmenttuple t) -> t.priority)).collect(Collectors.toList()));
        return frozen;
    }

    public static final RatePlanResolver Empty = Build(List.of());
}
