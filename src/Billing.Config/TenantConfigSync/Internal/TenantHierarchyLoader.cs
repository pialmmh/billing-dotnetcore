using System.Diagnostics;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Model;
using Billing.Config.TenantConfigSync.Publishes;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// Builds the whole tenant tree from config-manager and swaps it into the registry. Used both for the
/// one-time start-up load and for every config-event reload — same path, different trigger. One load
/// runs at a time (a reload can fire while start-up is still in flight); the gate serialises them.
/// </summary>
internal sealed class TenantHierarchyLoader
{
    private readonly IConfigManagerClient _client;
    private readonly TenantSelection _selection;
    private readonly TenantRegistryState _registry;
    private readonly TenantConfigSyncOptions _opts;
    private readonly ILogger<TenantHierarchyLoader> _log;
    private readonly SemaphoreSlim _gate = new(1, 1);

    /// <summary>Raised after each successful swap (metrics / cache warm-up can subscribe).</summary>
    public event EventHandler<ConfigReloadedEvent>? Reloaded;

    public TenantHierarchyLoader(IConfigManagerClient client, TenantSelection selection,
        TenantRegistryState registry, IOptions<TenantConfigSyncOptions> opts,
        ILogger<TenantHierarchyLoader> log)
    {
        _client = client;
        _selection = selection;
        _registry = registry;
        _opts = opts.Value;
        _log = log;
    }

    public async Task<ConfigReloadedEvent> LoadAllAsync(
        ConfigReloadTrigger trigger, string? eventId, CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            var sw = Stopwatch.StartNew();
            var roots = new List<Tenant>();

            foreach (var sel in _selection.Enabled)
            {
                Tenant tenant;
                try
                {
                    tenant = await _client.GetTenantRootAsync(sel.Name, ct);
                }
                catch (ConfigManagerUnavailableException ex)
                {
                    // Fail-fast: refuse to serve an empty/partial context (it would silently mis-rate).
                    // Nothing is swapped — on startup the app fails to start; on reload the last-good
                    // snapshot keeps serving. Loud, then propagate.
                    _log.LogError(ex,
                        "config {Trigger} ABORTED — config-manager unavailable for tenant {Tenant}; "
                        + "registry NOT swapped (keeping last-good config)", trigger, sel.Name);
                    throw;
                }

                if (_opts.DebugMode)
                {
                    _log.LogInformation("loaded tenant {Tenant} (db={Db}) partners={Partners} packages={Pkgs}",
                        tenant.Name, tenant.DbName, tenant.Context.Partners.Count,
                        tenant.Context.PartnerIdWisePackageAccounts.Count);
                }

                TenantTreeBuilder.Finalize(tenant);
                roots.Add(tenant);
            }

            _registry.Swap(roots);

            var evt = new ConfigReloadedEvent(trigger, roots.Count, sw.ElapsedMilliseconds, eventId);
            _log.LogInformation("config {Trigger}: {Ok} tenant(s) loaded, {Ms} ms{Evt}",
                trigger, evt.TenantsLoaded, evt.DurationMs,
                eventId is null ? "" : $" (eventId={eventId})");

            Reloaded?.Invoke(this, evt);
            return evt;
        }
        finally
        {
            _gate.Release();
        }
    }
}
