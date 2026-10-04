package com.telcobright.billing;

import com.telcobright.billing.StartEndpoints.Endpoint;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.DatasourceOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.ProfileConfigReaderAccess;
import com.telcobright.billing.tenantconfigsync.dependencies.SelectedTenant;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A start says the addresses it is about to dial before it dials one, and a LAB start
 * ({@code billing.lab.local-only=true}) is refused unless every one of them is this box. The jar carries the
 * profiles of real deployments: a lab start that fell back to one of them stops here, with nothing dialed.
 */
class StartEndpointsTests {

    private static final String ALabProfile =
            "billing:\n"
            + "  config-manager:\n    base-url: \"http://127.0.0.1:7754\"\n"
            + "  config-events:\n    enabled: true\n    bootstrap-servers: \"127.0.0.1:7792\"\n"
            + "  datasource:\n    kind: postgresql\n    host: \"127.0.0.1\"\n    port: 7743\n    database: \"routesphere\"\n    username: \"billing_core\"\n"
            + "  cdr-ingest:\n    enabled: true\n    bootstrap-servers: \"localhost:7792\"\n    topic: \"cdr_btcl\"\n"
            + "  summary:\n    enabled: true\n    bootstrap-servers: \"127.0.0.1:7792\"\n";

    private static StartEndpoints EndpointsOf(String profileYaml) {
        TenantConfigSyncOptions sync = ProfileConfigReaderAccess.Sync(profileYaml);
        DatasourceOptions ds = ProfileConfigReaderAccess.Datasource(profileYaml);
        CdrIngestOptions ingest = ProfileConfigReaderAccess.Ingest(profileYaml);
        SummaryOutboxOptions summary = ProfileConfigReaderAccess.Summary(profileYaml);
        return new StartEndpoints(sync, ds, ingest, summary);
    }

    /** A profile the jar itself carries: what a start reads when it does not find its run directory's own. */
    private static String TheJarsOwnProfile(String tenant, String profile) throws Exception {
        String resource = "config/tenants/" + tenant + "/" + profile + "/profile-" + profile + ".yml";
        try (InputStream in = StartEndpointsTests.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Config ConfigOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(properties, "test", 100)).build();
    }

    // ── said first ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void every_address_the_process_will_dial_is_listed_with_the_key_that_named_it() {
        assertEquals(List.of(
                new Endpoint("the tenant tree", "billing.config-manager.base-url", "http://127.0.0.1:7754"),
                new Endpoint("the doorbell's brokers", "billing.config-events.bootstrap-servers", "127.0.0.1:7792"),
                new Endpoint("the datasource", "billing.datasource.host", "postgresql://127.0.0.1:7743/routesphere"),
                new Endpoint("the cdr topic's brokers", "billing.cdr-ingest.bootstrap-servers", "localhost:7792"),
                new Endpoint("the summary ping's brokers", "billing.summary.bootstrap-servers", "127.0.0.1:7792")),
                EndpointsOf(ALabProfile).All());
    }

    @Test
    void a_mysql_datasource_is_listed_by_host_and_port() {
        StartEndpoints mysql = EndpointsOf("billing:\n  datasource:\n    host: \"10.0.0.5\"\n    username: \"u\"\n");

        assertTrue(mysql.All().contains(new Endpoint("the datasource", "billing.datasource.host", "mysql://10.0.0.5:3306")),
                mysql.All().toString());
    }

    @Test
    void a_block_that_is_switched_off_dials_nothing_and_is_not_listed() {
        StartEndpoints treeOnly = EndpointsOf("billing:\n  config-manager:\n    base-url: \"http://127.0.0.1:7754\"\n"
                + "  config-events:\n    enabled: false\n    bootstrap-servers: \"203.0.113.9:9092\"\n"
                + "  cdr-ingest:\n    enabled: false\n    bootstrap-servers: \"203.0.113.9:9092\"\n"
                + "  summary:\n    enabled: false\n    bootstrap-servers: \"203.0.113.9:9092\"\n");

        assertEquals(List.of(new Endpoint("the tenant tree", "billing.config-manager.base-url", "http://127.0.0.1:7754")),
                treeOnly.All());
        assertDoesNotThrow(() -> treeOnly.RefuseWhatIsNotThisBox(), "an address nobody dials refuses no start");
    }

    @Test
    void the_start_says_where_the_profile_was_read_and_then_one_line_for_each_endpoint() {
        List<String> lines = EndpointsOf(ALabProfile).Lines("tenant btcl, profile lab: the file /lab/config/tenants/btcl/lab/profile-lab.yml");

        assertEquals(6, lines.size(), lines.toString());
        assertEquals("endpoints of this start (nothing has been dialed yet) — tenant btcl, profile lab: the file"
                + " /lab/config/tenants/btcl/lab/profile-lab.yml", lines.get(0));
        assertEquals("endpoint: the tenant tree = http://127.0.0.1:7754  (billing.config-manager.base-url)", lines.get(1));
        assertEquals("endpoint: the datasource = postgresql://127.0.0.1:7743/routesphere  (billing.datasource.host)", lines.get(3));
    }

    // ── a lab start is judged ────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_lab_start_is_one_that_sets_the_key_and_no_other_start_is() {
        assertTrue(StartEndpoints.IsALabStart(ConfigOf(Map.of("billing.lab.local-only", "true"))));
        assertFalse(StartEndpoints.IsALabStart(ConfigOf(Map.of("billing.lab.local-only", "false"))));
        assertFalse(StartEndpoints.IsALabStart(ConfigOf(Map.of())), "a deployment does not set it: its start is only said");
    }

    @Test
    void a_lab_profile_that_names_only_this_box_starts_and_says_so() {
        List<String> said = new ArrayList<>();

        assertDoesNotThrow(() -> StartEndpoints.SaidAndJudged(EndpointsOf(ALabProfile), "the lab's own", true, said::add));

        assertEquals("billing.lab.local-only=true: every endpoint is on this box — the start goes on", said.get(said.size() - 1));
    }

    @Test
    void a_refused_lab_start_has_said_its_endpoints_first() throws Exception {
        List<String> said = new ArrayList<>();
        StartEndpoints fellBack = EndpointsOf(TheJarsOwnProfile("ccl78", "dev"));

        assertThrows(IllegalStateException.class, () -> StartEndpoints.SaidAndJudged(fellBack, "THE JAR'S OWN profile", true, said::add));

        assertEquals(fellBack.Lines("THE JAR'S OWN profile"), said, "the log of a refused start shows what it would have dialed");
    }

    @Test
    void a_start_that_is_not_a_labs_is_said_and_never_judged() throws Exception {
        List<String> said = new ArrayList<>();
        StartEndpoints aDeployment = EndpointsOf(TheJarsOwnProfile("ccl78", "dev"));

        assertDoesNotThrow(() -> StartEndpoints.SaidAndJudged(aDeployment, "a deployment's", false, said::add));

        assertEquals(aDeployment.Lines("a deployment's"), said);
    }

    @Test
    void a_lab_start_that_fell_back_to_a_profile_the_jar_carries_is_refused_and_every_endpoint_is_named() throws Exception {
        // The jar's own registry enables ccl78 / dev: what a start gets when its run directory's configuration is not found.
        StartEndpoints fellBack = EndpointsOf(TheJarsOwnProfile("ccl78", "dev"));

        IllegalStateException refused = assertThrows(IllegalStateException.class, fellBack::RefuseWhatIsNotThisBox);

        assertTrue(refused.getMessage().startsWith("REFUSING TO START (billing.lab.local-only=true)"), refused.getMessage());
        for (String key : List.of("billing.config-manager.base-url", "billing.config-events.bootstrap-servers",
                "billing.datasource.host", "billing.cdr-ingest.bootstrap-servers", "billing.summary.bootstrap-servers"))
            assertTrue(refused.getMessage().contains(key), "it names " + key + ": " + refused.getMessage());
    }

    @Test
    void one_endpoint_elsewhere_is_enough_to_refuse_and_only_it_is_named() {
        String oneBrokerElsewhere = ALabProfile.replace("bootstrap-servers: \"localhost:7792\"",
                "bootstrap-servers: \"127.0.0.1:7792,203.0.113.9:9092\"");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> EndpointsOf(oneBrokerElsewhere).RefuseWhatIsNotThisBox());

        assertTrue(refused.getMessage().contains("billing.cdr-ingest.bootstrap-servers: host 203.0.113.9"), refused.getMessage());
        assertFalse(refused.getMessage().contains("billing.datasource.host"), "what is on this box is not named");
    }

    @Test
    void the_host_is_read_out_of_a_url_a_broker_list_or_a_bare_name() {
        assertEquals(List.of("10.10.252.1"), StartEndpoints.HostsOf("http://10.10.252.1:7754"));
        assertEquals(List.of("127.0.0.1"), StartEndpoints.HostsOf("postgresql://127.0.0.1:7743/routesphere"));
        assertEquals(List.of("10.0.0.20", "10.0.0.21"), StartEndpoints.HostsOf("10.0.0.20:9092, 10.0.0.21:9092"));
        assertEquals(List.of("::1"), StartEndpoints.HostsOf("[::1]:9092"));
        assertEquals(List.of("::1"), StartEndpoints.HostsOf("http://[::1]:7754"));
        assertEquals(List.of("localhost"), StartEndpoints.HostsOf("localhost"));
        assertEquals(List.of(), StartEndpoints.HostsOf(""));
    }

    @Test
    void this_box_is_localhost_a_loopback_address_or_an_address_one_of_its_interfaces_holds() {
        assertTrue(StartEndpoints.IsThisBox("localhost"));
        assertTrue(StartEndpoints.IsThisBox("127.0.0.1"));
        assertTrue(StartEndpoints.IsThisBox("::1"));
        assertTrue(StartEndpoints.IsThisBox("10.10.252.1", held -> held.getHostAddress().equals("10.10.252.1")), "a lab's bridge");
        assertFalse(StartEndpoints.IsThisBox("10.10.252.2", held -> held.getHostAddress().equals("10.10.252.1")),
                "the next address of the same net is another box");
        assertFalse(StartEndpoints.IsThisBox("203.0.113.9"), "a documentation address: no interface holds it");
        assertFalse(StartEndpoints.IsThisBox("0.0.0.0"), "no address at all");
    }

    @Test
    void a_host_name_is_never_looked_up_and_never_this_box() {
        assertFalse(StartEndpoints.IsThisBox("ip6-localhost"), "a name this box's own hosts file knows is still a name");
        assertFalse(StartEndpoints.IsAnAddress("kafka.lab.internal"));
        assertFalse(StartEndpoints.IsThisBox("kafka.lab.internal", anyAddress -> true), "not even when every address were this box's");
        assertTrue(StartEndpoints.IsAnAddress("10.10.252.1"));
        assertTrue(StartEndpoints.IsAnAddress("fe80::1"));
    }

    // ── nothing is dialed first, by construction ─────────────────────────────────────────────────────────────

    @Test
    void the_four_blocks_that_hold_an_address_are_handed_out_only_by_the_endpoints_that_were_said() throws Exception {
        StartEndpoints said = EndpointsOf(ALabProfile);
        Map<String, Object> blockOfProducer = Map.of("tenantConfigSyncOptions", said.Sync(), "datasourceOptions", said.Datasource(),
                "cdrIngestOptions", said.Ingest(), "summaryOutboxOptions", said.Summary());

        for (Map.Entry<String, Object> producer : blockOfProducer.entrySet()) {
            Method method = OnlyMethodNamed(producer.getKey());

            assertArrayEquals(new Class<?>[]{StartEndpoints.class}, method.getParameterTypes(),
                    producer.getKey() + " takes its block from StartEndpoints and from nowhere else");
            assertSame(producer.getValue(), method.invoke(new BillingConfig(), said), producer.getKey() + " hands out the block that was said");
        }
    }

    @Test
    void the_composition_root_refuses_a_lab_start_that_reads_the_jars_own_registry_and_profile() {
        TenantSelection theJarsOwnRegistry = new TenantSelection();
        theJarsOwnRegistry.Tenants = List.of(new SelectedTenant("ccl78", true, "dev"));
        System.setProperty(StartEndpoints.LocalOnlyKey, "true");          // what the lab launcher passes as -D
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new BillingConfig().startEndpoints(theJarsOwnRegistry));

            assertTrue(refused.getMessage().startsWith("REFUSING TO START (billing.lab.local-only=true)"), refused.getMessage());
        } finally {
            System.clearProperty(StartEndpoints.LocalOnlyKey);
        }
        assertDoesNotThrow(() -> new BillingConfig().startEndpoints(theJarsOwnRegistry), "without the key the same start is only said");
    }

    private static Method OnlyMethodNamed(String name) {
        List<Method> found = new ArrayList<>();
        for (Method m : BillingConfig.class.getDeclaredMethods()) if (m.getName().equals(name)) found.add(m);
        assertEquals(1, found.size(), "one producer named " + name);
        return found.get(0);
    }
}
