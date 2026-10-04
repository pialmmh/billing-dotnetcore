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
 *
 * <p>A summary ping that cannot be published is a DETAIL of the same check ({@value #PingDetailKey}), not a reason to
 * answer DOWN: the rows are written and the summary service polls.
 */
@Readiness
@ApplicationScoped
public class CdrIngestHealthCheck implements HealthCheck {
    public static final String Name = "cdr-ingest";
    public static final String PingDetailKey = "summary-ping";

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
        if (!health.PingDetail().isEmpty()) answer.withData(PingDetailKey, health.PingDetail());
        return answer.build();
    }
}
