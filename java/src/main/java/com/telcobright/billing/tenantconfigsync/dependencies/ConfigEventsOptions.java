package com.telcobright.billing.tenantconfigsync.dependencies;

public final class ConfigEventsOptions {
    public boolean Enabled;
    public String BootstrapServers = "";
    public String EventTopicBase = "config_event_loader";
    public String ConsumerGroupBase = "billing-core-config-reload";
    public int DebounceMs = 3000;

    /** Idle longer than DebounceMs × this → reload immediately (leading-edge fast path). */
    public int IdleFastPathMultiplier = 2;
}
