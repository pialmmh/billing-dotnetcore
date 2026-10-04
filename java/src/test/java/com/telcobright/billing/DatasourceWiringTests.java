package com.telcobright.billing;

import com.telcobright.billing.beans.SummaryRollupConsumerRefusal;
import com.telcobright.billing.data.ITenantConnectionFactory;
import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.data.PostgresConnectionFactory;
import com.telcobright.billing.mediation.sql.SqlDialect;
import com.telcobright.billing.tenantconfigsync.dependencies.DatasourceOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.ProfileConfigReaderAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 — the write target is chosen per tenant profile ({@code billing.datasource.kind}): the composition root gives
 * the connection factory AND the batch runner of that engine, never one of each. And the one bean that is MySQL's
 * only — the in-process summary roll-up — refuses a PostgreSQL profile at start.
 */
class DatasourceWiringTests {
    private final BillingConfig wiring = new BillingConfig();

    private static DatasourceOptions Profile(String yaml) {
        return ProfileConfigReaderAccess.Datasource(yaml);
    }

    @Test
    void a_postgresql_profile_gets_postgresqls_connection_factory_and_postgresqls_runner() {
        DatasourceOptions ds = Profile("billing:\n  datasource:\n    kind: postgresql\n    host: \"10.10.199.20\"\n"
                + "    database: \"routesphere\"\n    username: \"billing_core\"\n");

        ITenantConnectionFactory connections = wiring.connectionFactory(ds);

        assertInstanceOf(PostgresConnectionFactory.class, connections);
        assertEquals(SqlDialect.PostgreSql, connections.Dialect());
        assertTrue(connections.IsConfigured());
        assertEquals(SqlDialect.PostgreSql, wiring.cdrBatchRunner(ds).Dialect());
    }

    @Test
    void a_profile_that_names_no_kind_gets_mysqls_as_before() {
        DatasourceOptions ds = Profile("billing:\n  datasource:\n    host: \"10.0.0.5\"\n    username: \"u\"\n");

        ITenantConnectionFactory connections = wiring.connectionFactory(ds);

        assertInstanceOf(MySqlConnectionFactory.class, connections);
        assertEquals(SqlDialect.MySql, connections.Dialect());
        assertEquals(SqlDialect.MySql, wiring.cdrBatchRunner(ds).Dialect());
    }

    @Test
    void a_postgresql_profile_without_its_switch_database_is_not_configured() {
        DatasourceOptions ds = Profile("billing:\n  datasource:\n    kind: postgresql\n    host: \"h\"\n    username: \"billing_core\"\n");

        assertTrue(!wiring.connectionFactory(ds).IsConfigured(), "no database named: an entry point refuses cleanly");
    }

    @Test
    void the_in_process_summary_roll_up_refuses_a_postgresql_datasource_in_words() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SummaryRollupConsumerRefusal.On(SqlDialect.PostgreSql));

        assertTrue(refused.getMessage().contains("billing.summary-rollup.enabled is true on a PostgreSQL datasource"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Set billing.summary-rollup.enabled: false"), refused.getMessage());
        assertDoesNotThrow(() -> SummaryRollupConsumerRefusal.On(SqlDialect.MySql));
    }

    // ── B10: the password comes from the environment where the profile names a variable ──────────────────────

    @Test
    void the_start_is_refused_when_the_variable_the_profile_names_is_not_in_the_environment() {
        String unset = "BC_TEST_A_VARIABLE_NOBODY_SETS_" + Long.toHexString(System.nanoTime()).toUpperCase();
        DatasourceOptions ds = Profile("billing:\n  datasource:\n    kind: postgresql\n    host: \"h\"\n    database: \"routesphere\"\n"
                + "    username: \"billing_core\"\n    password-ref: \"env:" + unset + "\"\n");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> wiring.connectionFactory(ds));

        assertTrue(refused.getMessage().startsWith("REFUSING TO START"), refused.getMessage());
        assertTrue(refused.getMessage().contains(unset), "it names the variable: " + refused.getMessage());
    }

    @Test
    void a_mysql_profile_may_name_a_variable_too_and_is_refused_the_same_way() {
        String unset = "BC_TEST_A_VARIABLE_NOBODY_SETS_" + Long.toHexString(System.nanoTime()).toUpperCase();
        DatasourceOptions ds = Profile("billing:\n  datasource:\n    host: \"h\"\n    username: \"u\"\n    password-ref: \"env:" + unset + "\"\n");

        assertThrows(IllegalStateException.class, () -> wiring.connectionFactory(ds));
    }
}
