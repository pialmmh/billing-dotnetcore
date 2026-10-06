package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.spi.ConfigManagerUnavailableException;
import com.telcobright.billing.testsupport.FakeConfigManagerServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection lifecycle of the config-manager client. Production finding (2026-10-07): every non-2xx answer left its
 * HTTP connection open — the error body was neither read nor closed — so each failed {@code /get-rates-by-date}
 * call stranded one socket (134 in CLOSE_WAIT within three hours, ~1/min). Each test drives the client with the
 * same {@code HttpClient.newHttpClient()} production uses against a raw-socket fake config-manager that counts the
 * connections the client still holds.
 */
class HttpConfigManagerClientConnectionTests {
    private static final int Calls = 20;
    private static final Duration Settle = Duration.ofSeconds(3);

    private FakeConfigManagerServer configManager;
    private HttpClient http;
    private HttpConfigManagerClient client;

    @BeforeEach
    void start() throws Exception {
        configManager = new FakeConfigManagerServer();
        http = HttpClient.newHttpClient();
        var opts = new TenantConfigSyncOptions();
        opts.ConfigManager.BaseUrl = configManager.BaseUrl();
        opts.ConfigManager.TimeoutSeconds = 5;
        client = new HttpConfigManagerClient(http, opts);
    }

    @AfterEach
    void stop() throws Exception {
        http.shutdownNow();
        configManager.close();
    }

    /** A Spring-style 500 error page, streamed in pieces (the shape config-manager sends). */
    private static List<String> ErrorPage(int pieces) {
        List<String> body = new ArrayList<>();
        body.add("{\"timestamp\":\"2026-10-07T01:00:00.000+00:00\",\"status\":500,\"error\":\"Internal Server Error\",");
        for (int i = 0; i < pieces; i++) body.add("\"trace\":\"java.lang.IllegalStateException: no rates endpoint " + i + "\",");
        body.add("\"path\":\"/get-rates-by-date\"}");
        return body;
    }

    @Test
    void a_500_from_rates_by_date_leaves_no_connection_open() throws Exception {
        configManager.Respond(500, ErrorPage(6));

        for (int i = 0; i < Calls; i++) {
            var ex = assertThrows(ConfigManagerUnavailableException.class,
                    () -> client.GetRatesForDate("tenant_a", LocalDate.of(2026, 10, 8)));
            assertTrue(ex.getMessage().contains("returned 500"), ex.getMessage());
        }

        assertEquals(Calls, configManager.Requests("/get-rates-by-date"), "every call reached config-manager");
        int open = configManager.OpenAfterSettling(1, Settle);
        assertTrue(open <= 1, () -> "connections still held by the client after " + Calls + " failed calls: " + open
                + " (accepted " + configManager.Accepted() + ")");
    }

    @Test
    void a_500_from_tenant_root_leaves_no_connection_open() throws Exception {
        configManager.Respond(500, ErrorPage(6));

        for (int i = 0; i < Calls; i++) {
            assertThrows(ConfigManagerUnavailableException.class, () -> client.GetTenantRoot("tenant_a"));
        }

        assertEquals(Calls, configManager.Requests("/get-specific-tenant-root"));
        int open = configManager.OpenAfterSettling(1, Settle);
        assertTrue(open <= 1, () -> "connections still held by the client: " + open + " (accepted " + configManager.Accepted() + ")");
    }

    @Test
    void successful_calls_leave_no_connection_open_either() throws Exception {
        configManager.Respond(200, List.of("{", "}"));

        for (int i = 0; i < Calls; i++) {
            assertTrue(client.GetRatesForDate("tenant_a", LocalDate.of(2026, 10, 8)).isEmpty());
        }

        int open = configManager.OpenAfterSettling(1, Settle);
        assertTrue(open <= 1, () -> "connections still held by the client: " + open);
    }
}
