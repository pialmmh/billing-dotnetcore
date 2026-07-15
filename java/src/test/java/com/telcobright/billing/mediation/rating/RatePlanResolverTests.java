package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rate-plan tuple resolution over the verbatim legacy tuples (legacy GetAssignmentTuples/GetRouteTuple/
 * GetPartnerTuple + the family GetServiceTuple): route scope beats partner scope beats service-wide scope. A
 * tuple with neither route nor partner is a SERVICE-WIDE assignment that applies to all partners of the
 * service+direction (legacy DicServiceTuples); a partner-specific tuple still overrides it.
 */
class RatePlanResolverTests {
    private static final int Customer = AssignmentDirection.Customer.value;
    private static final int Supplier = AssignmentDirection.Supplier.value;

    @Test
    void Resolves_partner_scope() {
        var r = RatePlanResolver.Build(List.of(TestData.tuple(10, Customer, 5, null, 0)));
        var tuples = r.Resolve(10, Customer, 5, null);
        assertEquals(1, tuples.size());
        assertEquals(5, tuples.get(0).idpartner);
    }

    @Test
    void Route_scope_is_preferred_over_partner_scope() {
        var r = RatePlanResolver.Build(List.of(
                TestData.tuple(10, Customer, 5, null, 0),
                TestData.tuple(10, Customer, null, 99, 0)));
        assertEquals(99, r.Resolve(10, Customer, 5, 99).get(0).route);       // route wins
        assertEquals(5, r.Resolve(10, Customer, 5, null).get(0).idpartner);  // fall back to partner
    }

    @Test
    void Returned_tuples_are_priority_ordered() {
        var r = RatePlanResolver.Build(List.of(
                TestData.tuple(11, Customer, 5, null, 5),
                TestData.tuple(11, Customer, 5, null, 1)));
        int[] priorities = r.Resolve(11, Customer, 5, null).stream().mapToInt(t -> t.priority).toArray();
        assertArrayEquals(new int[]{1, 5}, priorities);
    }

    @Test
    void Miss_returns_empty() {
        var r = RatePlanResolver.Build(List.of(TestData.tuple(10, Customer, 5, null, 0)));
        assertTrue(r.Resolve(10, Customer, 404, null).isEmpty());                 // unknown partner -> no default
        assertTrue(r.Resolve(99, Customer, 5, null).isEmpty());                   // unknown service group
        assertTrue(r.Resolve(10, Supplier, 5, null).isEmpty());                   // wrong direction
        assertTrue(RatePlanResolver.Empty.Resolve(10, Customer, 5, null).isEmpty());
    }

    @Test
    void Tuple_with_neither_route_nor_partner_is_service_wide() {
        // a non-partner/route tuple is a SERVICE-WIDE assignment: applies to ALL partners of the
        // service+direction (legacy DicServiceTuples / the family's GetServiceTuple).
        var r = RatePlanResolver.Build(List.of(TestData.tuple(20, Supplier, null, null, 0)));
        assertEquals(1, r.Resolve(20, Supplier, 5, null).size());     // any partner resolves it
        assertEquals(1, r.Resolve(20, Supplier, 777, null).size());   // any other partner too
        assertEquals(1, r.Resolve(20, Supplier, null, null).size());  // and a null-partner call
        assertTrue(r.Resolve(20, Customer, 5, null).isEmpty());       // but NOT the other direction (scoped)
        assertTrue(r.Resolve(21, Supplier, 5, null).isEmpty());       // NOT a different service
    }

    @Test
    void Partner_scope_wins_over_service_wide() {
        var r = RatePlanResolver.Build(List.of(
                TestData.tuple(10, Customer, 5, null, 0),          // partner-specific
                TestData.tuple(10, Customer, null, null, 0)));     // service-wide
        // partner 5 has a specific tuple -> that one wins (not the service-wide)
        assertEquals(5, r.Resolve(10, Customer, 5, null).get(0).idpartner);
        // partner 6 has no specific tuple -> falls back to the service-wide one
        var svc = r.Resolve(10, Customer, 6, null);
        assertEquals(1, svc.size());
        assertTrue(svc.get(0).idpartner == null || svc.get(0).idpartner == 0);
    }
}
