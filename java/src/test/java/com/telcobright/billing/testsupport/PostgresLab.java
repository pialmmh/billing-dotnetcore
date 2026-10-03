package com.telcobright.billing.testsupport;

import com.telcobright.billing.data.PostgresConnectionFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The throwaway PostgreSQL a lab test runs against — a developer's own container, as prime-context's
 * {@code docs/ad-as-call/postgres-tenancy.md} §4 makes it (trust on loopback: no password anywhere; the database
 * {@code routesphere}; the roles {@code prime_context}, {@code billing_core}, {@code summary_service},
 * {@code ad_sphere}):
 *
 * <pre>
 *   mvn test -Dbc.lab.pg.url=jdbc:postgresql://127.0.0.1:7743/routesphere
 * </pre>
 *
 * Without {@code -Dbc.lab.pg.url} every test that calls {@link #Required()} is SKIPPED, never passed.
 *
 * <p>{@link #FreshTenantSchema} makes a tenant's schema the way prime-context's provisioning does
 * ({@code SchemaSharing}): owned by {@code prime_context}, USAGE + CREATE to the three services, and the DEFAULT
 * PRIVILEGES that share what billing-core creates. billing-core's own tables are NOT made here — making them is
 * what the tests are about.
 */
public final class PostgresLab {
    private PostgresLab() {}

    public static final String UrlProperty = "bc.lab.pg.url";
    private static final Pattern Url = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/(\\w+)");

    public static final String BillingRole = "billing_core";
    public static final String SummaryRole = "summary_service";
    public static final String ReaderRole = "ad_sphere";
    private static final String SchemaOwner = "prime_context";

    /** What prime-context's default privileges give the summary service on billing-core's tables: its main today
     * ({@code SELECT, DELETE}), and after its W12 ({@code SELECT}). billing-core's own statement must hold on both. */
    public static final String SummaryOnBillingToday = "SELECT, DELETE";
    public static final String SummaryOnBillingAfterW12 = "SELECT";

    /** Skips the calling test when no lab is named. */
    public static void Required() {
        assumeTrue(System.getProperty(UrlProperty) != null,
                "no -D" + UrlProperty + ": the PostgreSQL lab tests are skipped, never passed");
    }

    private static Matcher Parsed() {
        Matcher m = Url.matcher(System.getProperty(UrlProperty, ""));
        if (!m.matches()) throw new IllegalStateException("-D" + UrlProperty + " must be jdbc:postgresql://<host>:<port>/<database>");
        return m;
    }

    public static String Host() { return Parsed().group(1); }
    public static int Port() { return Integer.parseInt(Parsed().group(2)); }
    public static String Database() { return Parsed().group(3); }

    /** billing-core's own way in: the role {@code billing_core}, a tenant's schema as the search path. */
    public static PostgresConnectionFactory Factory() {
        return new PostgresConnectionFactory(Host(), Port(), Database(), BillingRole, "");
    }

    /** A connection of any lab role, with the tenant's schema as its search path. */
    public static Connection As(String role, String schema) {
        Properties props = new Properties();
        props.setProperty("user", role);
        if (schema != null) props.setProperty("currentSchema", schema);
        try {
            return DriverManager.getConnection(System.getProperty(UrlProperty), props);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The lab's superuser ({@code -Dbc.lab.pg.admin}, default {@code postgres}) — what a DBA or prime-context does. */
    public static Connection Admin() {
        return As(System.getProperty("bc.lab.pg.admin", "postgres"), null);
    }

    public static void FreshTenantSchema(String schema) {
        FreshTenantSchema(schema, SummaryOnBillingToday);
    }

    /** Drop the schema if it is there, then make it as prime-context's provisioning does. */
    public static void FreshTenantSchema(String schema, String summaryOnBilling) {
        Exec(Admin(), true,
                "DROP SCHEMA IF EXISTS " + schema + " CASCADE",
                "CREATE SCHEMA " + schema + " AUTHORIZATION " + SchemaOwner,
                "GRANT USAGE, CREATE ON SCHEMA " + schema + " TO " + ReaderRole + ", " + BillingRole + ", " + SummaryRole,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + BillingRole + " IN SCHEMA " + schema + " GRANT SELECT ON TABLES TO " + ReaderRole,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + SummaryRole + " IN SCHEMA " + schema + " GRANT SELECT ON TABLES TO " + ReaderRole,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + BillingRole + " IN SCHEMA " + schema + " GRANT " + summaryOnBilling
                        + " ON TABLES TO " + SummaryRole);
    }

    public static void DropSchema(String schema) {
        Exec(Admin(), true, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    /** Run the statements as the lab's superuser. */
    public static void AsAdmin(String... statements) {
        Exec(Admin(), true, statements);
    }

    private static void Exec(Connection conn, boolean close, String... statements) {
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignored) { /* a test's connection */ }
        }
    }

    /** One value of one row, read as the lab's superuser (schema-qualified names). */
    public static String Scalar(String sql) {
        try (Connection conn = Admin(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static long Count(String qualifiedTable) {
        return Long.parseLong(Scalar("select count(*) from " + qualifiedTable));
    }

    /** The first column of every row, read as the lab's superuser. */
    public static List<String> Column(String sql) {
        List<String> values = new ArrayList<>();
        try (Connection conn = Admin(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) values.add(rs.getString(1));
            return values;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Try one statement as a role; the refusal's message, or null when the statement went through. */
    public static String RefusalOf(String role, String schema, String sql) {
        try (Connection conn = As(role, schema); Statement st = conn.createStatement()) {
            st.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }
}
