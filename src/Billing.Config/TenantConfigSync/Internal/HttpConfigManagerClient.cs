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
///
/// The tenant-root payload is large (tens of MB — the full tree, each tenant's whole context), so it is
/// <b>streamed</b>: headers are read first, then the body is deserialized straight off the network with
/// no full in-memory buffer. A per-call timeout (TimeoutSeconds) bounds the whole transfer + parse.
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
        // The whole operation (transfer + parse) is bounded per-call below; let HttpClient itself not
        // impose a shorter cap that would fire before the streamed body finishes.
        _http.Timeout = Timeout.InfiniteTimeSpan;
    }

    public async Task<Tenant> GetTenantRootAsync(string tenantName, CancellationToken ct)
    {
        var url = $"{_opts.ConfigManager.TenantRootEndpoint}?name={Uri.EscapeDataString(tenantName)}";

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(_opts.ConfigManager.TimeoutSeconds));
        var token = cts.Token;

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, url);
            using var resp = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
            if (!resp.IsSuccessStatusCode)
                throw new ConfigManagerUnavailableException(tenantName,
                    $"config-manager {_opts.ConfigManager.BaseUrl}{url} returned {(int)resp.StatusCode} for tenant '{tenantName}'");

            await using var stream = await resp.Content.ReadAsStreamAsync(token);
            var dto = await JsonSerializer.DeserializeAsync<TenantDto>(stream, Json, token);
            if (dto is null)
                throw new ConfigManagerUnavailableException(tenantName,
                    $"config-manager returned an empty body for tenant '{tenantName}'");

            return ConfigManagerMapper.ToTenant(dto);
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or JsonException or IOException)
        {
            var why = cts.IsCancellationRequested && !ct.IsCancellationRequested
                ? $"timed out after {_opts.ConfigManager.TimeoutSeconds}s"
                : "unreachable/invalid";
            throw new ConfigManagerUnavailableException(tenantName,
                $"config-manager {why} for tenant '{tenantName}' at {_opts.ConfigManager.BaseUrl}{url}", ex);
        }
    }
}
