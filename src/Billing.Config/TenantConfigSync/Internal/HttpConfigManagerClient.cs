using System.Net.Http.Json;
using System.Text.Json;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Config.TenantConfigSync.Model;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// Default <see cref="IConfigManagerClient"/>: POSTs <c>/get-specific-tenant-root?name=&lt;tenant&gt;</c>
/// and maps the JSON to the immutable Tenant tree. Depends only on BCL (HttpClient + System.Text.Json),
/// so the package needs no third-party HTTP stack. On any failure it returns null and logs — startup
/// degrades to an empty context for that tenant instead of crashing.
/// </summary>
internal sealed class HttpConfigManagerClient : IConfigManagerClient
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    private readonly HttpClient _http;
    private readonly TenantConfigSyncOptions _opts;
    private readonly ILogger<HttpConfigManagerClient> _log;

    public HttpConfigManagerClient(HttpClient http,
        IOptions<TenantConfigSyncOptions> opts, ILogger<HttpConfigManagerClient> log)
    {
        _opts = opts.Value;
        _log = log;
        _http = http;
        _http.BaseAddress = new Uri(_opts.ConfigManager.BaseUrl);
        _http.Timeout = TimeSpan.FromSeconds(_opts.ConfigManager.TimeoutSeconds);
    }

    public async Task<Tenant?> GetTenantRootAsync(string tenantName, CancellationToken ct)
    {
        var url = $"{_opts.ConfigManager.TenantRootEndpoint}?name={Uri.EscapeDataString(tenantName)}";
        try
        {
            using var resp = await _http.PostAsync(url, content: null, ct);
            if (!resp.IsSuccessStatusCode)
            {
                _log.LogWarning("config-manager {Url} returned {Status} for tenant {Tenant}",
                    url, (int)resp.StatusCode, tenantName);
                return null;
            }

            var dto = await resp.Content.ReadFromJsonAsync<TenantDto>(Json, ct);
            if (dto is null)
            {
                _log.LogWarning("config-manager returned empty body for tenant {Tenant}", tenantName);
                return null;
            }
            return ConfigManagerMapper.ToTenant(dto);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            _log.LogWarning(ex, "config-manager unreachable/invalid for tenant {Tenant} at {BaseUrl}{Url}",
                tenantName, _opts.ConfigManager.BaseUrl, url);
            return null;
        }
    }
}
