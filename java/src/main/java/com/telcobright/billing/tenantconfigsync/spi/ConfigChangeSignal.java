package com.telcobright.billing.tenantconfigsync.spi;

/** One config-change signal — "tenant X changed, re-fetch". Carries no config data. */
public record ConfigChangeSignal(String Tenant, int ChangeCount, String EventId) {
}
