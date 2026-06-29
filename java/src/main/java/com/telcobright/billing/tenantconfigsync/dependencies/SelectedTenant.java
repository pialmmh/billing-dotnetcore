package com.telcobright.billing.tenantconfigsync.dependencies;

/** One row of tenants.yml: a tenant this instance loads, and its active profile. */
public record SelectedTenant(String Name, boolean Enabled, String Profile) {
}
