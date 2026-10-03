package com.telcobright.billing.ingest;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * What the ingest does with a record whose tenant the loaded tree does not know (brief B8: "a reseller provisioned
 * at run time takes its first record").
 *
 * <p>A reseller's schema is made at run time; billing-core learns of it when prime-context rings the doorbell and
 * the tree is fetched again — a few seconds later (the ring, its debounce, the load). A view that arrives inside
 * those seconds names a tenant this process has not heard of yet. Dead-lettering it at once would lose the first
 * records of every new reseller. So an unknown tenant makes the ingest ASK THE TREE ITSELF, and only a tenant the
 * tree still does not know after that is a dead letter:
 *
 * <ul>
 *   <li>{@link Verdict#AskTheTree} — the tree was not asked for longer than the interval: fetch it now, then read
 *       the batch again;</li>
 *   <li>{@link Verdict#DeadLetter} — every unknown tenant of the batch was already refused by a tree fetched
 *       less than an interval ago: nothing new to learn, they are dead letters (a producer that keeps naming a
 *       tenant that does not exist costs one fetch an interval, not one per record);</li>
 *   <li>{@link Verdict#Wait} — a tenant the tree was NOT asked about, inside the interval: the batch is held (not
 *       written, not committed) until the next fetch is due. Bounded by the interval; nothing is lost.</li>
 * </ul>
 * The interval is the profile's {@code billing.cdr-ingest.unknown-tenant-reload-seconds} (default 30): the tree's
 * payload is large, and it must not be fetched once per poll.
 */
final class UnknownTenantGate {

    enum Verdict { AskTheTree, DeadLetter, Wait }

    private final long intervalMs;
    private final LongSupplier clock;
    private final Map<String, Long> refusedAt = new HashMap<>();   // tenant → when a freshly fetched tree did not know it
    private long askedAt = Long.MIN_VALUE / 2;                     // the last fetch this gate caused

    UnknownTenantGate(long intervalMs, LongSupplier clock) {
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    Verdict For(Set<String> unknownTenants) {
        long now = clock.getAsLong();
        if (unknownTenants.stream().allMatch(tenant -> RefusedRecently(tenant, now))) return Verdict.DeadLetter;
        return now - askedAt >= intervalMs ? Verdict.AskTheTree : Verdict.Wait;
    }

    /** The tree was fetched: these tenants are still not in it. */
    void TheTreeWasAsked(Set<String> stillUnknown) {
        long now = clock.getAsLong();
        askedAt = now;
        refusedAt.clear();
        for (String tenant : stillUnknown) refusedAt.put(tenant, now);
    }

    /** How long until the tree may be asked again, in ms (0 = now). */
    long WaitMs() {
        return Math.max(0, intervalMs - (clock.getAsLong() - askedAt));
    }

    private boolean RefusedRecently(String tenant, long now) {
        Long at = refusedAt.get(tenant);
        return at != null && now - at < intervalMs;
    }
}
