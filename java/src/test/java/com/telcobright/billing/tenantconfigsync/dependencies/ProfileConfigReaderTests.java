package com.telcobright.billing.tenantconfigsync.dependencies;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ── B1: a new consumer group starts at 'earliest' — a profile value (ad-is-a-call §4) ─────────────────────

    @Test
    void A_new_cdr_consumer_group_starts_at_earliest_unless_the_profile_says_latest() {
        CdrIngestOptions unset = ProfileConfigReader.ReadCdrIngestFromYaml(
                "billing:\n  cdr-ingest:\n    enabled: true\n    topic: \"cdr_btcl\"\n");
        CdrIngestOptions latest = ProfileConfigReader.ReadCdrIngestFromYaml(
                "billing:\n  cdr-ingest:\n    enabled: true\n    auto-offset-reset: \"Latest\"\n");
        CdrIngestOptions absentBlock = ProfileConfigReader.ReadCdrIngestFromYaml("billing:\n  datasource:\n    host: \"x\"\n");

        assertEquals("earliest", unset.AutoOffsetReset);       // the ratified default
        assertEquals("cdr_btcl", unset.Topic);
        assertEquals("latest", latest.AutoOffsetReset);        // a tenant's own choice, any letter case
        assertEquals("earliest", absentBlock.AutoOffsetReset);
    }

    @Test
    void An_offset_reset_word_kafka_does_not_know_is_refused_at_start() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ProfileConfigReader.ReadCdrIngestFromYaml(
                        "billing:\n  cdr-ingest:\n    enabled: true\n    auto-offset-reset: \"beginning\"\n"));

        assertTrue(refused.getMessage().contains("billing.cdr-ingest.auto-offset-reset"), refused.getMessage());
        assertTrue(refused.getMessage().contains("'beginning'"), refused.getMessage());
    }

    @Test
    void The_tries_before_a_held_batch_turns_the_health_road_red_are_a_profile_value() {
        CdrIngestOptions unset = ProfileConfigReader.ReadCdrIngestFromYaml(
                "billing:\n  cdr-ingest:\n    enabled: true\n    dead-letter-topic: \"cdr_dlq_btcl\"\n");
        CdrIngestOptions seven = ProfileConfigReader.ReadCdrIngestFromYaml(
                "billing:\n  cdr-ingest:\n    enabled: true\n    dead-letter-unhealthy-after-tries: 7\n");

        assertEquals(3, unset.DeadLetterUnhealthyAfterTries);
        assertEquals("cdr_dlq_btcl", unset.DeadLetterTopic);
        assertEquals(7, seven.DeadLetterUnhealthyAfterTries);
    }

    @Test
    void How_often_the_tree_is_asked_about_an_unknown_tenant_is_a_profile_value() {
        CdrIngestOptions unset = ProfileConfigReader.ReadCdrIngestFromYaml("billing:\n  cdr-ingest:\n    enabled: true\n");
        CdrIngestOptions tenSeconds = ProfileConfigReader.ReadCdrIngestFromYaml(
                "billing:\n  cdr-ingest:\n    enabled: true\n    unknown-tenant-reload-seconds: 10\n");

        assertEquals(30, unset.UnknownTenantReloadSeconds);
        assertEquals(10, tenSeconds.UnknownTenantReloadSeconds);
    }

    // ── B6: PostgreSQL as a write target, chosen per tenant profile ──────────────────────────────────────────

    @Test
    void A_postgresql_datasource_names_its_kind_its_switch_database_and_the_settings_of_its_tables() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(
                "billing:\n" +
                "  datasource:\n" +
                "    kind: PostgreSQL\n" +
                "    host: \"10.10.199.20\"\n" +
                "    database: \"routesphere\"\n" +
                "    username: \"billing_core\"\n" +
                "    postgres:\n" +
                "      summary-service-role: \"sum_ro\"\n" +
                "      reader-roles: [\"ad_sphere\", \"audit\"]\n" +
                "      months-back: 2\n" +
                "      months-ahead: 6\n");

        assertTrue(ds.IsPostgres());
        assertEquals("postgresql", ds.Kind);                   // any letter case in the file
        assertEquals("routesphere", ds.Database);
        assertEquals(5432, ds.Port, "PostgreSQL's port when the profile names none");
        assertEquals("sum_ro", ds.PostgresSummaryServiceRole);
        assertEquals(java.util.List.of("ad_sphere", "audit"), ds.PostgresReaderRoles);
        assertEquals(2, ds.PostgresMonthsBack);
        assertEquals(6, ds.PostgresMonthsAhead);
    }

    @Test
    void A_datasource_that_does_not_say_its_kind_is_mysql_as_every_profile_before() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(
                "billing:\n  datasource:\n    host: \"103.95.96.77\"\n    admin-db: \"telcobright\"\n");

        assertFalse(ds.IsPostgres());
        assertEquals("mysql", ds.Kind);
        assertEquals(3306, ds.Port);
        assertEquals("", ds.Database);
    }

    @Test
    void The_postgresql_tables_settings_have_defaults_and_an_empty_role_switches_its_grant_off() {
        DatasourceOptions defaults = ProfileConfigReader.ReadDatasourceFromYaml(
                "billing:\n  datasource:\n    kind: postgresql\n    host: \"h\"\n    port: 6432\n");
        DatasourceOptions off = ProfileConfigReader.ReadDatasourceFromYaml(
                "billing:\n  datasource:\n    kind: postgresql\n    host: \"h\"\n    postgres:\n"
                        + "      summary-service-role: \"\"\n      reader-roles: []\n");

        assertEquals(6432, defaults.Port);
        assertEquals("summary_service", defaults.PostgresSummaryServiceRole);
        assertEquals(java.util.List.of("ad_sphere"), defaults.PostgresReaderRoles);
        assertEquals(1, defaults.PostgresMonthsBack);
        assertEquals(3, defaults.PostgresMonthsAhead);
        assertEquals("", off.PostgresSummaryServiceRole);
        assertTrue(off.PostgresReaderRoles.isEmpty());
        assertEquals(1, off.PostgresMonthsBack, "a key that is absent keeps its default");
    }

    @Test
    void A_datasource_kind_that_is_neither_engine_is_refused_at_start() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ProfileConfigReader.ReadDatasourceFromYaml("billing:\n  datasource:\n    kind: oracle\n    host: \"h\"\n"));

        assertTrue(refused.getMessage().contains("billing.datasource.kind must be 'mysql' or 'postgresql', not 'oracle'"),
                refused.getMessage());
    }
}
