package com.telcobright.billing.tenantconfigsync.spi;

/**
 * Thrown when config-manager — the single source of truth for a tenant's config — cannot be read
 * (unreachable, error status, empty/invalid body, or no such tenant). It is fatal on purpose: the
 * system fails fast rather than serving an empty or partial context that would silently mis-rate.
 *
 * <p>Faithful-port note: C# {@code Exception} (unchecked) → Java {@code RuntimeException}, so the
 * synchronous fail-fast propagates without {@code throws} clauses. The C# get-only property
 * {@code TenantName} becomes the no-arg method {@link #TenantName()}.
 */
public final class ConfigManagerUnavailableException extends RuntimeException {
    private final String TenantName;

    public ConfigManagerUnavailableException(String tenantName, String message) {
        this(tenantName, message, null);
    }

    public ConfigManagerUnavailableException(String tenantName, String message, Throwable inner) {
        super(message, inner);
        this.TenantName = tenantName;
    }

    public String TenantName() {
        return TenantName;
    }
}
