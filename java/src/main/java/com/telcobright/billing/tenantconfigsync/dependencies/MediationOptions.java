package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * The active profile's {@code billing.mediation} block. Currently just the fixed {@code SwitchId} — the source
 * network element's {@code ne.idSwitch}, stamped onto every Kafka-ingested cdr so the summary's
 * {@code tup_switchid} matches legacy (where the switch id came from the NE that owned the CDR job).
 *
 * <p>The routesphere Kafka feed carries no switch/NE id and config-manager does not serve the {@code ne} table,
 * so on this path the switch id is deployment config (decision 2026-07-28). {@code 0} = unset (leaves
 * {@code cdr.SwitchId} at 0, the prior behaviour).
 */
public final class MediationOptions {
    public int SwitchId = 0;
}
