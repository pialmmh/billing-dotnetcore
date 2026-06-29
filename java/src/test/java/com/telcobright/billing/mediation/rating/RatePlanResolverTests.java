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
 * GetPartnerTuple): route scope beats partner scope, the returned tuples are priority-ordered, and a call that
 * matches neither route nor partner resolves to EMPTY — there is NO tenant-default fallback.
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
    void Tuple_with_neither_route_nor_partner_is_not_resolvable() {
        // a non-partner/route tuple is not indexed (legacy returned null for it) — no tenant default.
        var r = RatePlanResolver.Build(List.of(TestData.tuple(10, Customer, null, null, 0)));
        assertTrue(r.Resolve(10, Customer, 5, null).isEmpty());
        assertTrue(r.Resolve(10, Customer, null, null).isEmpty());
    }
}
