package com.telcobright.billing.mediation.cdr;

/** How a batch's summaries are persisted. */
public enum SummaryMode {
    /**
     * billing-core rolls up + writes the summaries itself, inline, in the batch transaction
     * (the legacy behaviour — {@code CdrSummaryContext}).
     */
    Inline,

    /**
     * billing-core writes the batch (compressed) to the {@code summary_affected} OUTBOX instead of
     * summarising; the decoupled summary-service consumes that row and writes the summaries. The outbox row
     * is written in the SAME transaction as the cdr/chargeable write, so it is atomic with them — which is
     * what sidesteps the dual-write (MySQL + Kafka can't commit together) problem.
     */
    Outbox,
}
