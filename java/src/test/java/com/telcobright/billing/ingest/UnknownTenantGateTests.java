package com.telcobright.billing.ingest;

import com.telcobright.billing.ingest.UnknownTenantGate.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B8 — when the tree is asked about a tenant it does not know yet, and when it is not (see {@link UnknownTenantGate}). */
class UnknownTenantGateTests {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final UnknownTenantGate gate = new UnknownTenantGate(30_000, now::get);

    @Test
    void the_first_unknown_tenant_makes_the_tree_be_asked() {
        assertEquals(Verdict.AskTheTree, gate.For(Set.of("res_45")));
    }

    @Test
    void a_tenant_the_tree_just_refused_is_a_dead_letter_without_asking_again() {
        gate.TheTreeWasAsked(Set.of("res_ghost"));
        now.addAndGet(29_999);

        assertEquals(Verdict.DeadLetter, gate.For(Set.of("res_ghost")));
    }

    @Test
    void another_unknown_tenant_inside_the_interval_waits_it_is_not_a_dead_letter() {
        gate.TheTreeWasAsked(Set.of("res_ghost"));
        now.addAndGet(5_000);

        assertEquals(Verdict.Wait, gate.For(Set.of("res_45")), "the tree was not asked about res_45");
        assertEquals(Verdict.Wait, gate.For(Set.of("res_ghost", "res_45")), "one tenant it was not asked about is enough");
        assertEquals(25_000, gate.WaitMs());
    }

    @Test
    void once_the_interval_is_over_the_tree_is_asked_again_even_about_a_tenant_it_refused() {
        gate.TheTreeWasAsked(Set.of("res_ghost"));
        now.addAndGet(30_000);

        assertEquals(Verdict.AskTheTree, gate.For(Set.of("res_ghost")), "it may exist by now");
        assertEquals(Verdict.AskTheTree, gate.For(Set.of("res_45")));
        assertEquals(0, gate.WaitMs());
    }

    @Test
    void a_tenant_the_tree_knew_at_the_last_fetch_is_not_remembered_as_refused() {
        gate.TheTreeWasAsked(Set.of("res_ghost"));
        now.addAndGet(1_000);
        gate.TheTreeWasAsked(Set.of());                       // asked again (the interval's guard is the caller's): now it knows it

        now.addAndGet(1_000);
        assertEquals(Verdict.Wait, gate.For(Set.of("res_ghost")), "not a dead letter: the last tree did not refuse it");
    }
}
