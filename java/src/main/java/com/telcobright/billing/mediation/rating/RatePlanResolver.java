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
 * {@code A2ZRater.GetAssignmentTuples / GetRouteTuple / GetPartnerTuple} plus the family-internal
 * {@code GetServiceTuple}: try ROUTE scope ({@code idService/dir/route}) first, then PARTNER scope
 * ({@code idService/dir/idpartner}), then SERVICE scope ({@code idService/dir}).
 *
 * <p>The SERVICE scope covers tuples with NO partner and NO route — a service-wide assignment that applies to
 * ALL partners of that service+direction (the legacy {@code DicServiceTuples}, used by service-wide families
 * such as the ICX supplier charge, whose {@code GetServiceTuple} keys by {@code idService} alone). We narrow
 * the key with the direction so a supplier-wide charge cannot resolve on a customer lookup (and vice versa);
 * the legacy tuple's own direction is preserved. The route/partner scopes still win when they match, so a
 * partner-specific rate overrides the service-wide one.
 *
 * <p>The matching tuples are returned PRIORITY-ordered (a partner/service can own several), and
 * {@code PrefixMatcher} longest-prefixes over their rates.
 */
public final class RatePlanResolver {
    private final Map<String, List<rateplanassignmenttuple>> _routeIndex;
    private final Map<String, List<rateplanassignmenttuple>> _partnerIndex;
    private final Map<String, List<rateplanassignmenttuple>> _serviceIndex;

    private RatePlanResolver(
            Map<String, List<rateplanassignmenttuple>> routeIndex,
            Map<String, List<rateplanassignmenttuple>> partnerIndex,
            Map<String, List<rateplanassignmenttuple>> serviceIndex) {
        this._routeIndex = routeIndex;
        this._partnerIndex = partnerIndex;
        this._serviceIndex = serviceIndex;
    }

    public static RatePlanResolver Build(List<rateplanassignmenttuple> tuples) {
        var route = new HashMap<String, List<rateplanassignmenttuple>>();
        var partner = new HashMap<String, List<rateplanassignmenttuple>>();
        var service = new HashMap<String, List<rateplanassignmenttuple>>();

        for (var t : tuples) {
            if (t.route != null && t.route > 0)
                route.computeIfAbsent(RouteKey(t.idService, t.AssignDirection, t.route), k -> new ArrayList<>()).add(t);
            else if (t.idpartner != null && t.idpartner > 0)
                partner.computeIfAbsent(PartnerKey(t.idService, t.AssignDirection, t.idpartner), k -> new ArrayList<>()).add(t);
            else
                // neither route nor partner -> a SERVICE-WIDE assignment (applies to all partners of the
                // service+direction). Legacy DicServiceTuples / the family's GetServiceTuple.
                service.computeIfAbsent(ServiceKey(t.idService, t.AssignDirection), k -> new ArrayList<>()).add(t);
        }

        return new RatePlanResolver(Freeze(route), Freeze(partner), Freeze(service));
    }

    /**
     * The tuples that apply to the call: ROUTE scope, then PARTNER scope, then SERVICE-WIDE scope;
     * priority-ordered; empty if none matches. PrefixMatcher then longest-prefixes over their rates.
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
        // no partner/route-specific match -> the service-wide assignment for this service+direction, if any.
        var st = _serviceIndex.get(ServiceKey(idService, assignDirection));
        if (st != null) return st;
        return List.of();
    }

    private static String RouteKey(int idService, int dir, int route) {
        return idService + "/" + dir + "/" + route;
    }

    private static String PartnerKey(int idService, int dir, int idPartner) {
        return idService + "/" + dir + "/" + idPartner;
    }

    private static String ServiceKey(int idService, int dir) {
        return idService + "/" + dir;
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
