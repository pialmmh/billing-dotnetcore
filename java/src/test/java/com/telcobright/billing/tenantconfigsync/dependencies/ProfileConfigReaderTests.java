package com.telcobright.billing.tenantconfigsync.dependencies;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader splits two concerns (routesphere convention):
 *  - the tenant REGISTRY (which tenants load + active profile) comes from application.properties
 *    (modelled here with a built MP Config), and
 *  - the per-profile DETAIL (datasource, summary, …) is parsed from the kebab-case profile YAML.
 *
 * The YAML parsing is tested directly via the package-private {@code *FromYaml} helpers (no temp files,
 * no classpath fiddling), and carries no credentials of its own.
 */
class ProfileConfigReaderTests {

    @Test
    void Reads_tenant_registry_from_config() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("billing.tenants[0].name", "ccl78");
        props.put("billing.tenants[0].enabled", "true");
        props.put("billing.tenants[0].profile", "dev");
        props.put("billing.tenants[1].name", "other");
        props.put("billing.tenants[1].enabled", "false");
        props.put("billing.tenants[1].profile", "prod");
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(props, "test", 100))
                .build();

        TenantSelection sel = ProfileConfigReader.ReadSelection(config);

        assertEquals(2, sel.Tenants.size());                 // both registered
        assertEquals(1, sel.Enabled().size());               // only the enabled one loads
        assertEquals("ccl78", sel.Enabled().get(0).Name());
        assertEquals("dev", sel.Enabled().get(0).Profile()); // active profile per tenant
    }

    @Test
    void Reads_datasource_block_with_kebab_keys() {
        String yaml =
                "billing:\n" +
                "  datasource:\n" +
                "    host: \"10.0.0.5\"\n" +
                "    port: 3306\n" +
                "    admin-db: \"telcobright\"\n" +
                "    reseller-db-prefix: \"res_\"\n" +
                "    username: \"billing_user\"\n" +
                "    password: \"s3cr3t\"\n";

        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(yaml);

        assertTrue(ds.IsConfigured());
        assertEquals("10.0.0.5", ds.Host);
        assertEquals(3306, ds.Port);
        assertEquals("telcobright", ds.AdminDb);
        assertEquals("res_", ds.ResellerDbPrefix);
        assertEquals("billing_user", ds.Username);
        assertEquals("s3cr3t", ds.Password);
    }

    @Test
    void Reads_summary_block_with_kebab_keys() {
        String yaml =
                "billing:\n" +
                "  summary:\n" +
                "    enabled: true\n" +
                "    entity-type: \"cdr\"\n" +
                "    ping-topic: \"cdr_summary_ping\"\n" +
                "    bootstrap-servers: \"103.95.96.78:9092\"\n";

        SummaryOutboxOptions s = ProfileConfigReader.ReadSummaryFromYaml(yaml);

        assertTrue(s.Enabled);
        assertEquals("cdr", s.EntityType);
        assertEquals("cdr_summary_ping", s.PingTopic);
        assertEquals("103.95.96.78:9092", s.BootstrapServers);
    }

    @Test
    void Summary_defaults_to_disabled_when_block_absent() {
        String yaml = "billing:\n  datasource:\n    host: \"x\"\n";

        SummaryOutboxOptions s = ProfileConfigReader.ReadSummaryFromYaml(yaml);

        assertFalse(s.Enabled);                            // off by default = legacy inline summaries
        assertEquals("cdr_summary_ping", s.PingTopic);     // default preserved when unset
    }
}
