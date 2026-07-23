package com.telcobright.billing.beans;

import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.data.MySqlSummaryBatchRunner;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryRollupOptions;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The summary ROLL-UP consumer loop — the decoupled service that turns each tenant's {@code summary_affected}
 * outbox into {@code sum_voice_day/hr} rows. Every {@code poll-ms} it sweeps each tenant schema and drains its
 * new outbox rows (page by page) through {@link MySqlSummaryBatchRunner}, which folds them BY SERVICE GROUP,
 * TUPLE-WISE, in one transaction with the advancing cursor. Mirrors the CDR ingest loop
 * ({@link com.telcobright.billing.ingest.CdrKafkaConsumer}) — a single daemon thread, config-gated, that
 * waits for the first tenant-config load before touching a schema.
 *
 * <p>Roll-up is PULL (poll the durable outbox) rather than driven by the best-effort
 * {@code cdr_summary_ping} — the ping only reduces latency; the outbox row is the durable hand-off.</p>
 */
@Singleton
public class SummaryRollupConsumer {
    private static final Logger log = Logger.getLogger(SummaryRollupConsumer.class);
    private static final int ErrorBackoffSeconds = 5;

    private final ITenantRegistry tenants;
    private final MySqlConnectionFactory connections;
    private final MySqlSummaryBatchRunner runner;
    private final SummaryRollupOptions opts;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "summary-rollup-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    @Inject
    public SummaryRollupConsumer(ITenantRegistry tenants, MySqlConnectionFactory connections,
            MySqlSummaryBatchRunner runner, SummaryRollupOptions opts) {
        this.tenants = tenants;
        this.connections = connections;
        this.runner = runner;
        this.opts = opts;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!opts.Enabled) {
            log.info("summary roll-up disabled (billing.summary-rollup.enabled=false) — outbox rows accumulate until a consumer drains them");
            return;
        }
        if (!connections.IsConfigured()) {
            log.warn("summary roll-up enabled but datasource credentials are not configured — NOT starting");
            return;
        }
        exec.submit(this::run);
        log.infof("summary roll-up consumer running (entity=%s, poll=%dms, maxRows=%d)",
                opts.EntityType, opts.PollMs, opts.MaxRowsPerPoll);
    }

    @PreDestroy
    void onStop() {
        running = false;
        exec.shutdownNow();
    }

    private void run() {
        boolean warned = false;
        boolean waitedForConfig = false;
        while (running) {
            // HOLD OFF until the tenant registry has its first config load — otherwise there are no schemas to
            // sweep (same rationale as the cdr ingest loop). Waiting costs nothing: the outbox rows stay put.
            if (!tenants.IsLoaded()) {
                if (!waitedForConfig) {
                    log.info("summary roll-up waiting for the first tenant-config load before sweeping");
                    waitedForConfig = true;
                }
                sleep(500);
                continue;
            }
            try {
                int folded = SweepAllTenants();
                if (folded > 0) log.infof("summary roll-up folded %d call(s) this sweep", folded);
                warned = false;
            } catch (Exception ex) {
                if (!warned) {
                    log.warnf("summary roll-up sweep error (%s); retrying every %ds until it clears",
                            ex.getMessage(), ErrorBackoffSeconds);
                    warned = true;
                }
                sleep(ErrorBackoffSeconds * 1000L);
                continue;
            }
            sleep(opts.PollMs);
        }
    }

    /** Drain every tenant schema's outbox once; a per-tenant failure is isolated (logged, retried next sweep). */
    private int SweepAllTenants() {
        int total = 0;
        for (String db : TenantDbNames()) {
            if (!running) break;
            try (Connection conn = connections.Open(db)) {
                MySqlSummaryBatchRunner.EnsureOffsetTable(conn);   // outside the sweep tx (DDL implicitly commits)
                while (running) {
                    MySqlSummaryBatchRunner.Result r =
                            runner.Run(conn, opts.EntityType, opts.MaxRowsPerPoll, opts.SegmentSize);
                    total += r.callsFolded();
                    if (r.rowsConsumed() < opts.MaxRowsPerPoll) break;   // a short page means this tenant is drained
                }
            } catch (Exception ex) {
                log.warnf("summary roll-up for tenant %s failed (isolated; retried next sweep): %s", db, ex.getMessage());
            }
        }
        return total;
    }

    /** Every schema in every loaded tenant tree (root + all reseller nodes), de-duplicated. */
    private Set<String> TenantDbNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Tenant root : tenants.Roots()) {
            if (root == null) continue;
            if (root.Index != null && !root.Index.isEmpty()) {
                for (Tenant t : root.Index.values())
                    if (t != null && t.DbName != null) names.add(t.DbName);
            } else if (root.DbName != null) {
                names.add(root.DbName);
            }
        }
        return names;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
