package com.telcobright.billing.tenantconfigsync.dependencies;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profile reader maps the kebab-case YAML (admin-db, reseller-db-prefix, secret-ref) onto the typed
 * DatasourceOptions, and carries no credentials.
 *
 * <p>Faithful-port note: C# {@code Directory.CreateTempSubdirectory()} per test → a JUnit {@code @TempDir}
 * field (a fresh temp dir is injected per test method); {@code File.WriteAllText} → {@code Files.writeString}.</p>
 */
class ProfileConfigReaderTests {

    @TempDir
    Path dir;

    @Test
    void Reads_datasource_block_with_kebab_keys() throws IOException {
        Path profileDir = dir.resolve("tenants").resolve("ccl").resolve("dev");
        Files.createDirectories(profileDir);
        Files.writeString(dir.resolve("tenants.yml"),
                "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        Files.writeString(profileDir.resolve("profile-dev.yml"),
                "billing:\n" +
                "  datasource:\n" +
                "    host: \"10.0.0.5\"\n" +
                "    port: 3306\n" +
                "    admin-db: \"telcobright\"\n" +
                "    reseller-db-prefix: \"res_\"\n" +
                "    username: \"billing_user\"\n" +
                "    password: \"s3cr3t\"\n");

        TenantSelection selection = ProfileConfigReader.ReadSelection(dir.toString());
        DatasourceOptions ds = ProfileConfigReader.ReadDatasource(dir.toString(), selection);

        assertTrue(ds.IsConfigured());
        assertEquals("10.0.0.5", ds.Host);
        assertEquals(3306, ds.Port);
        assertEquals("telcobright", ds.AdminDb);
        assertEquals("res_", ds.ResellerDbPrefix);
        assertEquals("billing_user", ds.Username);
        assertEquals("s3cr3t", ds.Password);
    }

    @Test
    void Reads_summary_block_with_kebab_keys() throws IOException {
        Path profileDir = dir.resolve("tenants").resolve("ccl").resolve("dev");
        Files.createDirectories(profileDir);
        Files.writeString(dir.resolve("tenants.yml"),
                "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        Files.writeString(profileDir.resolve("profile-dev.yml"),
                "billing:\n" +
                "  summary:\n" +
                "    enabled: true\n" +
                "    entity-type: \"cdr\"\n" +
                "    ping-topic: \"cdr_summary_ping\"\n" +
                "    bootstrap-servers: \"103.95.96.78:9092\"\n");

        SummaryOutboxOptions s = ProfileConfigReader.ReadSummary(dir.toString(), ProfileConfigReader.ReadSelection(dir.toString()));

        assertTrue(s.Enabled);
        assertEquals("cdr", s.EntityType);
        assertEquals("cdr_summary_ping", s.PingTopic);
        assertEquals("103.95.96.78:9092", s.BootstrapServers);
    }

    @Test
    void Summary_defaults_to_disabled_when_block_absent() throws IOException {
        Path profileDir = dir.resolve("tenants").resolve("ccl").resolve("dev");
        Files.createDirectories(profileDir);
        Files.writeString(dir.resolve("tenants.yml"),
                "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        Files.writeString(profileDir.resolve("profile-dev.yml"),
                "billing:\n  datasource:\n    host: \"x\"\n");

        SummaryOutboxOptions s = ProfileConfigReader.ReadSummary(dir.toString(), ProfileConfigReader.ReadSelection(dir.toString()));

        assertFalse(s.Enabled);                            // off by default = legacy inline summaries
        assertEquals("cdr_summary_ping", s.PingTopic);     // default preserved when unset
    }
}
