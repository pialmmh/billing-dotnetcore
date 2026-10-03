package com.telcobright.billing.tenantconfigsync.publishes;

public enum ConfigReloadTrigger {
    Startup,
    ConfigEvent,
    DayRollover,
    /** The cdr ingest met a record for a tenant the loaded tree does not know (a reseller provisioned at run time,
     * before its doorbell was heard): it asks the tree itself. */
    UnknownTenant
}
