using System.Net.Http.Json;
using System.Text.Json;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Config.TenantConfigSync.Model;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// Default <see cref="IConfigManagerClient"/>: POSTs <c>/get-specific-tenant-root?name=&lt;tenant&gt;</c>
/// and maps the JSON to the immutable Tenant tree. Depends only on BCL (HttpClient + System.Text.Json),
/// so the package needs no third-party HTTP stack. On any failure it throws
/// <see cref="ConfigManagerUnavailableException"/> — fail-fast, never a null/empty context.
/// </summary>
internal sealed class HttpConfigManagerClient : IConfigManagerClient
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    private readonly HttpClient _http;
    private readonly TenantConfigSyncOptions _opts;

    public HttpConfigManagerClient(HttpClient http, IOptions<TenantConfigSyncOptions> opts)
    {
        _opts = opts.Value;
        _http = http;
        _http.BaseAddress = new Uri(_opts.ConfigManager.BaseUrl);
        _http.Timeout = TimeSpan.FromSeconds(_opts.ConfigManager.TimeoutSeconds);
    }

    public async Task<Tenant> GetTenantRootAsync(string tenantName, CancellationToken ct)
    {
        var url = $"{_opts.ConfigManager.TenantRootEndpoint}?name={Uri.EscapeDataString(tenantName)}";
        try
        {
            using var resp = await _http.PostAsync(url, content: null, ct);
            if (!resp.IsSuccessStatusCode)
                throw new ConfigManagerUnavailableException(tenantName,
                    $"config-manager {_opts.ConfigManager.BaseUrl}{url} returned {(int)resp.StatusCode} for tenant '{tenantName}'");

            var dto = await resp.Content.ReadFromJsonAsync<TenantDto>(Json, ct);
            if (dto is null)
                throw new ConfigManagerUnavailableException(tenantName,
                    $"config-manager returned an empty body for tenant '{tenantName}'");

            return ConfigManagerMapper.ToTenant(dto);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            throw new ConfigManagerUnavailableException(tenantName,
                $"config-manager unreachable/invalid for tenant '{tenantName}' at {_opts.ConfigManager.BaseUrl}{url}", ex);
        }
    }
}
