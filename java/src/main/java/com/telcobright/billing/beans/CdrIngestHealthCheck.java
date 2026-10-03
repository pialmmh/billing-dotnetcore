package com.telcobright.billing.beans;

import com.telcobright.billing.ingest.IngestHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The health road's word on the cdr ingest: {@code /q/health} (and {@code /q/health/ready}) answer DOWN, with the
 * reason, while the ingest is refused at start or holds a batch whose dead letters cannot be published — the two
 * states in which records wait and nothing moves ({@link IngestHealth}). A window's gate and a monitor read this
 * road; the same reason is an ERROR line in the log.
 */
@Readiness
@ApplicationScoped
public class CdrIngestHealthCheck implements HealthCheck {
    public static final String Name = "cdr-ingest";

    private final IngestHealth health;

    @Inject
    public CdrIngestHealthCheck(IngestHealth health) {
        this.health = health;
    }

    @Override
    public HealthCheckResponse call() {
        IngestHealth.State state = health.Current();
        HealthCheckResponseBuilder answer = HealthCheckResponse.named(Name).status(state.Up());
        if (!state.Up()) answer.withData("reason", state.Reason());
        return answer.build();
    }
}
