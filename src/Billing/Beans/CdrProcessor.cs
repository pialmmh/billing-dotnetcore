using Billing.Config.TenantConfigSync.Api;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Data;
using Billing.Mediation.Cdr;
using MediationModel;
using Microsoft.Extensions.Options;

namespace Billing.Service.Beans;

/// <summary>
/// The CDR PROCESSOR — the service's main startup bean. It owns the cdr processing flow end to end:
/// resolve a tenant's rating/mediation config from <b>config-manager</b> (the <see cref="ITenantRegistry"/>
/// kept in sync underneath by the config-sync adapter), then mediate + write the batch in ONE transaction
/// via <see cref="MySqlCdrBatchRunner"/>, and — in OUTBOX mode — notify the summary-service after commit.
///
/// <para>It is the bean every entry point feeds: the gRPC <c>ProcessCdrBatch</c> RPC today, and a Kafka cdr
/// ingest loop next (launched from <see cref="StartAsync"/>). The config-sync consumer and the summary
/// notifier are dependencies it uses underneath — not beans themselves.</para>
/// </summary>
public sealed class CdrProcessor : IHostedService
{
    private readonly ITenantRegistry _tenants;            // config-manager view, kept in sync underneath
    private readonly MySqlConnectionFactory _connections;
    private readonly MySqlCdrBatchRunner _batchRunner;
    private readonly SummaryOutboxOptions _summary;
    private readonly SummaryChangeNotificationPublisher _summaryPublisher;
    private readonly ILogger<CdrProcessor> _log;

    public CdrProcessor(ITenantRegistry tenants, MySqlConnectionFactory connections,
        MySqlCdrBatchRunner batchRunner, IOptions<SummaryOutboxOptions> summary,
        SummaryChangeNotificationPublisher summaryPublisher, ILogger<CdrProcessor> log)
    {
        _tenants = tenants;
        _connections = connections;
        _batchRunner = batchRunner;
        _summary = summary.Value;
        _summaryPublisher = summaryPublisher;
        _log = log;
    }

    /// <summary>Startup seam: this is where the Kafka cdr ingest loop will be launched (read a batch →
    /// <see cref="ProcessBatch"/>). Not wired yet — the cdr topic + wire format are still to be defined, so
    /// today cdrs arrive via the gRPC entry. Kept here so the bean IS the startup component.</summary>
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _log.LogInformation("CdrProcessor started (gRPC-fed; Kafka cdr ingest loop pending a defined contract)");
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>Process ONE tenant's cdr batch end to end: resolve the tenant's config from config-manager,
    /// then mediate + write (cdr + acc_chargeable + summaries-or-outbox) in one transaction; OUTBOX mode
    /// notifies the summary-service after commit. Never throws — a failure is returned as a non-committed
    /// result (the transaction itself has already rolled back).</summary>
    public CdrProcessingResult ProcessBatch(string tenant, IReadOnlyList<cdr> cdrs)
    {
        var resolved = _tenants.FindByDbName(tenant);
        if (resolved is null)
            return CdrProcessingResult.Failed($"unknown tenant '{tenant}'");
        if (!_connections.IsConfigured)
            return CdrProcessingResult.Failed("datasource credentials not configured (set billing.datasource.username / password in profile-<env>.yml)");

        try
        {
            // OUTBOX mode writes the compressed batch to summary_affected (atomic with the cdr write) instead of
            // rolling up summaries inline; INLINE (default) keeps the legacy in-transaction summary write.
            var mode = _summary.Enabled ? SummaryMode.Outbox : SummaryMode.Inline;
            using var conn = _connections.Open(tenant);
            var r = _batchRunner.Run(conn, resolved.Context.MediationContext, resolved.Context.Partners, cdrs, summary: mode);

            // after the commit, nudge the summary-service (best-effort; it also polls the outbox).
            if (_summary.Enabled)
                _summaryPublisher.Publish(tenant, _summary.EntityType, r.Rated.Count);

            _log.LogInformation(
                "CdrProcessor tenant={Tenant} cdrs={Count} rated={Rated} errored={Errored} charged={Total} mode={Mode}",
                tenant, cdrs.Count, r.Rated.Count, r.Errored.Count, r.TotalCharged, mode);

            return CdrProcessingResult.Ok(r);
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "CdrProcessor tenant={Tenant} rolled back", tenant);
            return CdrProcessingResult.Failed(ex.Message);
        }
    }
}

/// <summary>The outcome of <see cref="CdrProcessor.ProcessBatch"/>: either committed (with the engine's
/// <see cref="CdrBatchResult"/>) or a non-committed failure with the reason. Entry-point adapters map this
/// onto their own response shape (e.g. the gRPC reply).</summary>
public sealed record CdrProcessingResult(bool Committed, string? Error, CdrBatchResult? Batch)
{
    public static CdrProcessingResult Ok(CdrBatchResult batch) => new(true, null, batch);
    public static CdrProcessingResult Failed(string error) => new(false, error, null);
}
