package com.telcobright.billing.tenantconfigsync.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.spi.ConfigManagerUnavailableException;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * Default {@link IConfigManagerClient}: POSTs {@code /get-specific-tenant-root?name=<tenant>}
 * and maps the JSON to the immutable Tenant tree. Depends only on the JDK HttpClient + Jackson,
 * so the package needs no extra HTTP stack. On any failure it throws
 * {@link ConfigManagerUnavailableException} — fail-fast, never a null/empty context.
 *
 * <p>The tenant-root payload is large (tens of MB — the full tree, each tenant's whole context), so it is
 * <b>streamed</b>: the response is read as an {@link InputStream} and deserialized straight off the network
 * with no full in-memory buffer. A per-call timeout (TimeoutSeconds, applied to the request) bounds the
 * transfer + parse.
 *
 * <p>Faithful-port note: System.Text.Json → Jackson with case-insensitive property matching
 * (mirrors the C# {@code PropertyNameCaseInsensitive = true}) and unknown properties ignored
 * (the System.Text.Json default). C# {@code Task<Tenant> GetTenantRootAsync(..., CancellationToken)}
 * is ported synchronous (RULE 2): the per-request timeout replaces the CancellationToken's CancelAfter.
 * C# {@code internal} → Java {@code public} so the registration glue can wire it.
 */
public final class HttpConfigManagerClient implements IConfigManagerClient {

    private static final ObjectMapper Json = JsonMapper.builder()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .addModule(new JavaTimeModule())   // rateassign rows (rateAssignsCustomer/Supplier) carry start/end dates
        .build();

    private final HttpClient _http;
    private final TenantConfigSyncOptions _opts;
    private final String _baseUrl;

    public HttpConfigManagerClient(HttpClient http, TenantConfigSyncOptions opts) {
        _opts = opts;
        _http = http;
        String base = opts.ConfigManager.BaseUrl;
        _baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public Tenant GetTenantRoot(String tenantName) {
        String url = _opts.ConfigManager.TenantRootEndpoint
            + "?name=" + URLEncoder.encode(tenantName, StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_baseUrl + url))
                .timeout(Duration.ofSeconds(_opts.ConfigManager.TimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<InputStream> resp = _http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status < 200 || status >= 300) {
                throw new ConfigManagerUnavailableException(tenantName,
                    "config-manager " + _baseUrl + url + " returned " + status + " for tenant '" + tenantName + "'");
            }

            try (InputStream stream = resp.body()) {
                TenantDto dto = Json.readValue(stream, TenantDto.class);
                if (dto == null) {
                    throw new ConfigManagerUnavailableException(tenantName,
                        "config-manager returned an empty body for tenant '" + tenantName + "'");
                }
                // Thread this client + the cache window into each tenant's RateCache so it can back-fill older
                // days on demand (today/tomorrow ride this snapshot).
                return ConfigManagerMapper.ToTenant(dto, this, _opts.ConfigManager.RateCacheMaxDays);
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String why = ex instanceof HttpTimeoutException
                ? "timed out after " + _opts.ConfigManager.TimeoutSeconds + "s"
                : "unreachable/invalid";
            throw new ConfigManagerUnavailableException(tenantName,
                "config-manager " + why + " for tenant '" + tenantName + "' at " + _baseUrl + url, ex);
        }
    }

    /**
     * On-demand rates for one date, for the RateCache's lazy back-fill of older days (back-processing). Live
     * query on the config-manager side (not its cached snapshot). Same failure contract as GetTenantRoot.
     */
    @Override
    public Map<Integer, Map<String, Rate>> GetRatesForDate(String tenantDbName, LocalDate date) {
        String url = _opts.ConfigManager.RatesByDateEndpoint
            + "?name=" + URLEncoder.encode(tenantDbName, StandardCharsets.UTF_8)
            + "&date=" + date;   // ISO-8601 yyyy-MM-dd

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_baseUrl + url))
                .timeout(Duration.ofSeconds(_opts.ConfigManager.TimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<InputStream> resp = _http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status < 200 || status >= 300) {
                throw new ConfigManagerUnavailableException(tenantDbName,
                    "config-manager " + _baseUrl + url + " returned " + status + " for '" + tenantDbName + "' " + date);
            }

            try (InputStream stream = resp.body()) {
                Map<Integer, Map<String, Rate>> rates =
                    Json.readValue(stream, new TypeReference<Map<Integer, Map<String, Rate>>>() {});
                return rates != null ? rates : Map.of();
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String why = ex instanceof HttpTimeoutException
                ? "timed out after " + _opts.ConfigManager.TimeoutSeconds + "s"
                : "unreachable/invalid";
            throw new ConfigManagerUnavailableException(tenantDbName,
                "config-manager rates-by-date " + why + " for '" + tenantDbName + "' " + date + " at " + _baseUrl + url, ex);
        }
    }
}
