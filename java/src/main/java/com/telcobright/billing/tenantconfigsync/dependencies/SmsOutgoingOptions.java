package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * Profile-bound config for the OUTGOING-SMS Kafka intake — the {@code billing.mediation.sms-outgoing} block:
 * <pre>
 * billing:
 *   mediation:
 *     sms-outgoing:
 *       enabled: false
 *       tenant: ""                # the tenant schema (dbName) the SMS CDRs bill into
 *       topic: ""                 # the SMS CDR topic — NO default, it must be configured
 *       group: ""                 # the consumer group — NO default, it must be configured
 *       bootstrap-servers: ""     # optional; falls back to billing.cdr-ingest.bootstrap-servers
 *       poll-ms: 500
 *       legacy-dedup-enabled: true
 *       id-block-size: 50
 *       auto-offset-reset: latest
 * </pre>
 * FAIL-SAFE: the consumer starts only when {@link #NotRunnableReason()} is null — enabled AND tenant, topic, group
 * and a broker are all present. No topic or group name is ever invented.
 */
public final class SmsOutgoingOptions {
    public boolean Enabled;
    public String Tenant = "";
    public String Topic = "";
    public String Group = "";
    public String BootstrapServers = "";
    public int PollMs = 500;
    /**
     * Drop an SMS whose SequenceNumber (= idCall) is already in cdr/cdrerror — the legacy-ownership guard for the
     * legacy→new cutover. ON by default for SMS (the redelivery guard on UniqueBillId is always on).
     */
    public boolean LegacyDedupEnabled = true;
    /** Ids reserved per counter round trip (gaps are harmless; small keeps ids close to legacy's). */
    public int IdBlockSize = 50;
    /** Where a NEW consumer group starts: {@code latest} (default) or {@code earliest}. */
    public String AutoOffsetReset = "latest";

    /** Null when the intake may start; otherwise why it must stay disabled. */
    public String NotRunnableReason() {
        if (!Enabled) return "billing.mediation.sms-outgoing.enabled=false";
        if (Blank(Tenant)) return "billing.mediation.sms-outgoing.tenant is not set";
        if (Blank(Topic)) return "billing.mediation.sms-outgoing.topic is not set";
        if (Blank(Group)) return "billing.mediation.sms-outgoing.group is not set";
        if (Blank(BootstrapServers)) return "no Kafka bootstrap servers (sms-outgoing.bootstrap-servers / cdr-ingest.bootstrap-servers)";
        if (!"latest".equals(AutoOffsetReset) && !"earliest".equals(AutoOffsetReset))
            return "billing.mediation.sms-outgoing.auto-offset-reset must be latest or earliest, got '" + AutoOffsetReset + "'";
        return null;
    }

    /** True when {@code tenant} is the configured SMS tenant (enabled or not). */
    public boolean IsSmsTenant(String tenant) {
        return !Blank(Tenant) && Tenant.equals(tenant);
    }

    private static boolean Blank(String s) {
        return s == null || s.isBlank();
    }
}
