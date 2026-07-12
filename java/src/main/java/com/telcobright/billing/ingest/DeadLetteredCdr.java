package com.telcobright.billing.ingest;

/**
 * A record the preprocessor could not decode/validate/map — routed to the dead-letter path instead of
 * poisoning the poll-batch (contract §3.5, §6). {@code payload} is the best-effort JSON of the offending
 * record (or the raw Kafka value when it would not even decode); {@code reason} says why it was rejected.
 * The consumer publishes these to {@code cdr_rated_dlq}.
 */
public record DeadLetteredCdr(String payload, String reason) {
}
