package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Classifies WHY a billable call's CUSTOMER (revenue) leg is or isn't chargeable, so the pipeline can route
 * on the rating OUTCOME rather than on the monetary amount alone (the required design principle: a
 * {@code BilledAmount == 0} must NOT by itself mean "error", because internal / BDIX / configured-free calls
 * legitimately rate to zero).
 *
 * <p>The decision matrix (for a duration-bearing / billable call — {@code duration == 0} failed calls never
 * reach here; they stay in the normal {@code cdr} pipeline for ASR):</p>
 * <pre>
 *   no customer leg (no rate matched)                         -> RATE_NOT_FOUND        -> cdrerror
 *   customer rate  &gt; 0                                         -> RATE_FOUND            -> cdr
 *   customer rate == 0 AND prefix declared free               -> INTENTIONALLY_FREE    -> cdr
 *   customer rate == 0 AND prefix NOT declared free           -> UNEXPECTED_ZERO_RATE  -> cdrerror
 *   customer rate == 0 AND no free set configured (yet)       -> GUARD_INACTIVE_NO_FREE_SET -> cdr (safe fallback)
 * </pre>
 *
 * <p>"declared free" is {@link MediationContext#FreePrefixes} — an EXPLICIT per-tenant declaration, never
 * inferred from the amount, service group, or route (the audit proved free short-codes and paid calls share
 * SG10 / plan 1 / the same routes; only the matched prefix differs). When the free set is empty the guard is
 * INACTIVE: a matched-zero call keeps the pre-guard behavior (written to {@code cdr}), so activating the guard
 * is a deliberate, per-tenant step (populate the free set) with no deploy-time regression.</p>
 *
 * <p>Nothing here reprocesses or recovers: a call routed to {@code cdrerror} stays there, with its precise
 * reason, until an operator fixes the root cause and manually reprocesses.</p>
 */
public final class ZeroRateGuard {
    private ZeroRateGuard() {}

    public enum Status {
        /** customer rate &gt; 0 — normal billed call. */                       RATE_FOUND(false),
        /** customer rate == 0 and the prefix is declared free. */              INTENTIONALLY_FREE(false),
        /** free set not configured yet — guard inactive, keep in cdr. */       GUARD_INACTIVE_NO_FREE_SET(false),
        /** no customer chargeable produced (no rate matched). */               RATE_NOT_FOUND(true),
        /** customer rate == 0 but the prefix is NOT declared free. */          UNEXPECTED_ZERO_RATE(true);

        /** true when this outcome must route the call to {@code cdrerror}. */
        public final boolean toCdrError;
        Status(boolean toCdrError) { this.toCdrError = toCdrError; }
    }

    /** The classification plus the matched customer prefix (for a precise error reason). */
    public record Result(Status status, String customerPrefix) {}

    /**
     * Classify the customer (revenue) leg of an already-rated, BILLABLE call. Call this ONLY when
     * {@code DurationSec > 0} and the checklist validation has already passed — a {@code duration == 0} failed
     * call must never be classified here (it belongs in {@code cdr} for ASR).
     */
    public static Result classify(List<acc_chargeable> chargeables, MediationContext mediation) {
        acc_chargeable customer = null;
        for (acc_chargeable c : chargeables) {
            if (c.assignedDirection != null && c.assignedDirection == (byte) AssignmentDirection.Customer.value) {
                customer = c;
                break;
            }
        }
        if (customer == null) return new Result(Status.RATE_NOT_FOUND, null);

        BigDecimal rate = customer.unitPriceOrCharge;
        if (rate != null && rate.signum() > 0) return new Result(Status.RATE_FOUND, customer.Prefix);

        // customer rate is zero (or absent on a produced chargeable) — decide free vs accidental by the
        // EXPLICIT free-prefix declaration, never by the amount itself.
        Set<String> free = mediation.FreePrefixes;
        if (free == null || free.isEmpty())
            return new Result(Status.GUARD_INACTIVE_NO_FREE_SET, customer.Prefix);
        if (customer.Prefix != null && free.contains(customer.Prefix))
            return new Result(Status.INTENTIONALLY_FREE, customer.Prefix);
        return new Result(Status.UNEXPECTED_ZERO_RATE, customer.Prefix);
    }
}
