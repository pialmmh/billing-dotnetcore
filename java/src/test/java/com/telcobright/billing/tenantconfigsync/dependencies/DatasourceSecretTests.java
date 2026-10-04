package com.telcobright.billing.tenantconfigsync.dependencies;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B10 — on the wifi bed the datasource's password comes from the unit's environment, never from the YAML: the
 * profile names the variable ({@code password-ref: env:<VAR>}). The inline form stays for the other deployments.
 * A variable that is not set is a refusal to start that names it; nothing here ever prints a value.
 */
class DatasourceSecretTests {
    private static final String BedProfile =
            "billing:\n" +
            "  datasource:\n" +
            "    kind: postgresql\n" +
            "    host: \"10.10.199.20\"\n" +
            "    database: \"routesphere\"\n" +
            "    username: \"billing_core\"\n" +
            "    password-ref: \"env:TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD\"\n";

    private static final Map<String, String> TheUnitsEnvironment = Map.of("TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD", "from-the-environment");

    @Test
    void the_profile_names_the_variable_and_the_password_is_read_from_the_environment_only() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(BedProfile);

        assertEquals("env:TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD", ds.PasswordRef);
        assertEquals("", ds.Password, "nothing of the password is in the YAML");
        assertEquals("from-the-environment", DatasourceSecret.PasswordOf(ds, TheUnitsEnvironment::get));
    }

    @Test
    void a_variable_that_is_not_set_is_a_refusal_to_start_that_names_it() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(BedProfile);

        for (Map<String, String> environment : new Map[] {Map.of(), Map.of("TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD", "")}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> DatasourceSecret.PasswordOf(ds, environment::get));

            assertTrue(refused.getMessage().startsWith("REFUSING TO START"), refused.getMessage());
            assertTrue(refused.getMessage().contains("TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD"), refused.getMessage());
            assertTrue(refused.getMessage().contains("no fallback"), refused.getMessage());
        }
    }

    @Test
    void the_inline_form_stays_for_the_other_deployments() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(
                "billing:\n  datasource:\n    host: \"10.0.0.5\"\n    username: \"u\"\n    password: \"inline-value\"\n");

        assertEquals("inline-value", DatasourceSecret.PasswordOf(ds, name -> { throw new AssertionError("the environment is not read"); }));
    }

    @Test
    void a_profile_with_neither_form_has_an_empty_password_as_before() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml("billing:\n  datasource:\n    host: \"10.0.0.5\"\n    username: \"u\"\n");

        assertEquals("", DatasourceSecret.PasswordOf(ds, name -> null));
    }

    @Test
    void naming_the_password_twice_is_refused() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(BedProfile + "    password: \"also-inline\"\n");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> DatasourceSecret.PasswordOf(ds, TheUnitsEnvironment::get));

        assertTrue(refused.getMessage().contains("names its password twice"), refused.getMessage());
        assertFalse(refused.getMessage().contains("also-inline"), "a message never carries a value");
        assertFalse(refused.getMessage().contains("from-the-environment"));
    }

    @Test
    void a_reference_that_is_not_env_colon_name_is_refused() {
        for (String ref : new String[] {"vault:secret/billing", "env:", "env:has space", "TENANT_BTCL_PASSWORD", "env:9STARTS_WITH_A_DIGIT"}) {
            DatasourceOptions ds = new DatasourceOptions();
            ds.PasswordRef = ref;

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> DatasourceSecret.PasswordOf(ds, TheUnitsEnvironment::get), ref);

            assertTrue(refused.getMessage().contains("must be 'env:<VARIABLE NAME>'"), refused.getMessage());
        }
    }

    @Test
    void a_secret_typed_into_password_ref_is_refused_and_not_printed() {
        String typedIntoTheWrongKey = "S3cr3t-typed-where-the-name-belongs";
        DatasourceOptions wrongKey = new DatasourceOptions();
        wrongKey.PasswordRef = typedIntoTheWrongKey;
        DatasourceOptions both = new DatasourceOptions();
        both.PasswordRef = typedIntoTheWrongKey;
        both.Password = "also-inline";

        for (DatasourceOptions ds : new DatasourceOptions[] {wrongKey, both}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> DatasourceSecret.PasswordOf(ds, TheUnitsEnvironment::get));

            assertFalse(refused.getMessage().contains(typedIntoTheWrongKey), "not printed: " + refused.getMessage());
        }
    }

    @Test
    void no_message_of_a_refusal_carries_the_value_of_any_variable() {
        DatasourceOptions ds = ProfileConfigReader.ReadDatasourceFromYaml(BedProfile.replace("BILLING_CORE_PASSWORD", "OTHER"));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> DatasourceSecret.PasswordOf(ds, TheUnitsEnvironment::get));

        assertFalse(refused.getMessage().contains("from-the-environment"));
    }
}
