package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.cdr;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What makes a record "the same record again" in a tenant's schema: the column that holds its call key, and the
 * tables in which a record that was already taken can sit. A record whose key is found there — or twice in one
 * batch — is dropped BEFORE mediation: no second row, no second chargeable, no second share of the summary outbox.
 *
 * <ul>
 *   <li>{@link #ChannelCallUuid} — the ratified key (ad-is-a-call §4): one record per call per tier, looked for in
 *       {@code cdr} AND {@code cdrerror}. PostgreSQL. A record that arrives without a call uuid (the gRPC
 *       entries) takes its {@code UniqueBillId} as one, so a single key serves every entry.</li>
 *   <li>{@link #UniqueBillId} — MySQL, as it has been since T3: looked for in {@code cdr} only. The MySQL tables
 *       have no {@code ChannelCallUuid} column, and {@code cdrerror} is not searched there (no index is known on
 *       it): a redelivered record that sits in {@code cdrerror} is mediated again on MySQL.</li>
 * </ul>
 */
public final class IdempotencyKey {

    public static final IdempotencyKey UniqueBillId =
            new IdempotencyKey("UniqueBillId", List.of("cdr"), c -> c.UniqueBillId, c -> { });

    public static final IdempotencyKey ChannelCallUuid =
            new IdempotencyKey("ChannelCallUuid", List.of("cdr", "cdrerror"), c -> c.ChannelCallUuid,
                    c -> { if (IsBlank(c.ChannelCallUuid)) c.ChannelCallUuid = c.UniqueBillId; });

    private final String column;
    private final List<String> tables;
    private final Function<cdr, String> of;
    private final Consumer<cdr> stamp;

    private IdempotencyKey(String column, List<String> tables, Function<cdr, String> of, Consumer<cdr> stamp) {
        this.column = column;
        this.tables = tables;
        this.of = of;
        this.stamp = stamp;
    }

    /** The column that holds the key, in each of {@link #Tables()}. */
    public String Column() {
        return column;
    }

    /** The tables a record that was already taken can sit in. */
    public List<String> Tables() {
        return tables;
    }

    /** Give the record its key where it has none of its own (see {@link #ChannelCallUuid}). */
    public void StampOn(cdr record) {
        stamp.accept(record);
    }

    /** The record's key, or null / empty when it has none — such a record cannot be told from another and is
     * always kept: the producer must supply the key for at-least-once to be safe. */
    public String Of(cdr record) {
        return of.apply(record);
    }

    public static boolean IsBlank(String key) {
        return key == null || key.isEmpty();
    }
}
