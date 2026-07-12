package com.telcobright.billing.beans;

import com.telcobright.billing.data.MySqlCdrBatchRunner;
import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.ingest.CdrKafkaConsumer;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.List;

/**
 * The CDR PROCESSOR — the service's main startup bean. It owns the cdr processing flow end to end:
 * resolve a tenant's rating/mediation config from <b>config-manager</b> (the {@link ITenantRegistry} kept in
 * sync underneath by the config-sync adapter), then mediate + write the batch in ONE transaction via
 * {@link MySqlCdrBatchRunner}, and notify the summary-service after commit (best-effort).
 *
 * <p>It is the bean every entry point feeds: the gRPC {@code ProcessCdrBatch} RPC today, and a Kafka cdr
 * ingest loop next (launched from the startup observer). The config-sync consumer and the summary notifier
 * are dependencies it uses underneath — not beans themselves.
 */
@Singleton
public class CdrProcessor {
    private static final Logger log = Logger.getLogger(CdrProcessor.class);

    private final ITenantRegistry tenants;            // config-manager view, kept in sync underneath
    private final MySqlConnectionFactory connections;
    private final MySqlCdrBatchRunner batchRunner;
    private final SummaryOutboxOptions summary;
    private final SummaryChangeNotificationPublisher summaryPublisher;
    private final CdrIngestOptions cdrIngest;

    private CdrKafkaConsumer cdrConsumer;             // started on onStart when cdr ingest is enabled

    @Inject
    public CdrProcessor(ITenantRegistry tenants, MySqlConnectionFactory connections,
            MySqlCdrBatchRunner batchRunner, SummaryOutboxOptions summary,
            SummaryChangeNotificationPublisher summaryPublisher, CdrIngestOptions cdrIngest) {
        this.tenants = tenants;
        this.connections = connections;
        this.batchRunner = batchRunner;
        this.summary = summary;
        this.summaryPublisher = summaryPublisher;
        this.cdrIngest = cdrIngest;
    }

    /** Startup seam: launch the inbound Kafka cdr ingest loop (poll -> preprocess -> ProcessBatch). When cdr
     * ingest is disabled (or no broker configured) the loop is not started and cdrs arrive via the gRPC entry.
     * Mirrors the .NET IHostedService.StartAsync + BillingBootstrap's config-event source wiring. */
    void onStart(@Observes StartupEvent ev) {
        cdrConsumer = CdrKafkaConsumer.Start(this, tenants, cdrIngest, log);
        if (cdrConsumer == null)
            log.info("CdrProcessor started (gRPC-fed; Kafka cdr ingest loop not running)");
        else
            log.info("CdrProcessor started (Kafka cdr ingest loop running)");
    }

    @PreDestroy
    void onStop() {
        if (cdrConsumer != null) cdrConsumer.stop();
    }

    /**
     * Process ONE tenant's cdr batch end to end: resolve the tenant's config from config-manager, then
     * mediate + write (cdr + acc_chargeable + the summary_affected outbox row) in one transaction; then
     * notify the summary-service after commit (best-effort). Never throws — a failure is returned as a
     * non-committed result (the transaction itself has already rolled back).
     */
    public CdrProcessingResult ProcessBatch(String tenant, List<cdr> cdrs) {
        Tenant resolved = tenants.FindByDbName(tenant);
        if (resolved == null)
            return CdrProcessingResult.Failed("unknown tenant '" + tenant + "'");
        if (!connections.IsConfigured())
            return CdrProcessingResult.Failed("datasource credentials not configured (set billing.datasource.username / password in profile-<env>.yml)");

        CdrBatchResult r = null;
        try (Connection conn = connections.Open(tenant)) {
            // The pipeline always writes ONE compressed summary_affected outbox row (atomic with the cdr write);
            // the standalone summary-service consumes it. (The old inline summary roll-up has been removed.)
            r = batchRunner.Run(conn, resolved.Context.MediationContext, resolved.Context.Partners, cdrs);
        } catch (Exception ex) {
            // r stays null when Run itself failed (the batch rolled back). r non-null means the commit
            // SUCCEEDED and the exception came after — realistically conn.close() — so the batch IS
            // durable: report success, or the caller would retry an already-committed batch (double bill).
            if (r == null) {
                log.error("CdrProcessor tenant=" + tenant + " rolled back", ex);
                return CdrProcessingResult.Failed(ex.getMessage());
            }
            log.warn("CdrProcessor tenant=" + tenant + " committed, but closing the connection failed", ex);
        }

        // after the commit, nudge the summary-service (best-effort; it also polls the outbox).
        if (summary.Enabled)
            summaryPublisher.Publish(tenant, summary.EntityType, r.Rated().size());

        log.infof("CdrProcessor tenant=%s cdrs=%d rated=%d errored=%d charged=%s",
                tenant, cdrs.size(), r.Rated().size(), r.Errored().size(), r.TotalCharged());

        return CdrProcessingResult.Ok(r);
    }
}
