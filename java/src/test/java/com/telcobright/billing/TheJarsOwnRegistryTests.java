package com.telcobright.billing;

import com.telcobright.billing.tenantconfigsync.dependencies.ProfileConfigReader;
import com.telcobright.billing.tenantconfigsync.dependencies.SelectedTenant;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BC-0004 F1 — the jar, started without a configuration of its own, used to BE a real deployment: its own
 * {@code application.properties} enabled {@code ccl78} / {@code dev}, a profile that names boxes that run today.
 * <ul>
 *   <li>the jar's own registry enables NO tenant (it names none at all);</li>
 *   <li>a start with no tenant enabled is refused, in words that say how a deployment names one;</li>
 *   <li>the profiles a deployment may name are still in the jar: a deployment that puts its three registry lines
 *       into its own {@code config/application.properties} runs as before.</li>
 * </ul>
 */
class TheJarsOwnRegistryTests {

    /** The registry the JAR carries: {@code application.properties} as it is on the class path, and nothing else. */
    private static TenantSelection TheJarsOwnRegistry() throws Exception {
        Properties bundled = new Properties();
        try (InputStream in = TheJarsOwnRegistryTests.class.getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in, "the jar's application.properties");
            bundled.load(in);
        }
        Map<String, String> properties = new HashMap<>();
        for (String key : bundled.stringPropertyNames()) properties.put(key, bundled.getProperty(key));
        return ProfileConfigReader.ReadSelection(
                new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(properties, "the jar's own", 100)).build());
    }

    private static TenantSelection ARegistryOf(SelectedTenant... tenants) {
        TenantSelection selection = new TenantSelection();
        selection.Tenants = List.of(tenants);
        return selection;
    }

    @Test
    void the_jar_itself_enables_no_tenant_and_names_none() throws Exception {
        TenantSelection bundled = TheJarsOwnRegistry();

        assertTrue(bundled.Enabled().isEmpty(), "no tenant is enabled by the jar: " + bundled.Tenants);
        assertTrue(bundled.Tenants.isEmpty(), "and none is named, not even switched off: the jar is no deployment's");
    }

    @Test
    void a_start_with_no_tenant_enabled_is_refused_in_words_that_say_how_a_deployment_names_one() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ProfileConfigReader.RefuseARegistryThatEnablesNobody(ARegistryOf()));

        String words = refused.getMessage();
        assertTrue(words.startsWith("REFUSING TO START: no tenant is enabled. The jar itself enables none."), words);
        assertTrue(words.contains("config/application.properties"), "where: " + words);
        for (String line : List.of("billing.tenants[0].name=<tenant>", "billing.tenants[0].enabled=true", "billing.tenants[0].profile=<profile>"))
            assertTrue(words.contains(line), "it gives the line " + line + ": " + words);
    }

    @Test
    void a_registry_whose_only_tenant_is_switched_off_is_refused_too() {
        assertThrows(IllegalStateException.class,
                () -> ProfileConfigReader.RefuseARegistryThatEnablesNobody(ARegistryOf(new SelectedTenant("ccl78", false, "dev"))));
    }

    @Test
    void a_registry_that_enables_a_tenant_is_taken_as_it_is() {
        TenantSelection aDeployments = ARegistryOf(new SelectedTenant("ccl78", true, "dev"));

        assertSame(aDeployments, ProfileConfigReader.RefuseARegistryThatEnablesNobody(aDeployments));
    }

    @Test
    void the_composition_root_refuses_a_start_whose_configuration_enables_nobody_and_takes_one_that_names_a_tenant() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new BillingConfig().tenantSelection());
        assertTrue(refused.getMessage().startsWith("REFUSING TO START: no tenant is enabled."), refused.getMessage());

        System.setProperty("billing.tenants[0].name", "btcl");                 // what a deployment's own file says
        System.setProperty("billing.tenants[0].enabled", "true");
        System.setProperty("billing.tenants[0].profile", "lab");
        try {
            TenantSelection named = new BillingConfig().tenantSelection();

            assertEquals(List.of(new SelectedTenant("btcl", true, "lab")), named.Enabled());
        } finally {
            for (String key : List.of("name", "enabled", "profile")) System.clearProperty("billing.tenants[0]." + key);
        }
    }

    @Test
    void the_profiles_a_deployment_may_name_are_still_in_the_jar() {
        for (String profile : List.of("dev", "prod"))
            assertNotNull(getClass().getClassLoader().getResource("config/tenants/ccl78/" + profile + "/profile-" + profile + ".yml"),
                    "ccl78 / " + profile + ": its three registry lines, in its own config/application.properties, are all it needs");
    }
}
