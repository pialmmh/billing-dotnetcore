package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Opens a connection to a tenant's SCHEMA of the one switch database (ad-is-a-call §3: "a MySQL tenant database is
 * a PostgreSQL schema"; the database's name is the profile's {@code billing.datasource.database}).
 *
 * <p>The connection's search path is the tenant's schema and nothing else ({@code currentSchema}), so the
 * pipeline's unqualified {@code cdr}, {@code acc_chargeable}, … are that tenant's and can never be {@code public}'s.
 * A tenant whose schema is not in the database — or that this role may not use — is refused here, in words, instead
 * of failing later on a table that "does not exist".
 *
 * <p>The session reads a string literal the standard way ({@code standard_conforming_strings = on}): that is what
 * {@code PostgresLiterals} writes for, whatever the server's default is.
 */
public final class PostgresConnectionFactory implements ITenantConnectionFactory {
    /** A tenant is a schema name and is never quoted (prime-context's rule): lower case, digits, underscore. */
    private static final Pattern SchemaName = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final String _host;
    private final int _port;
    private final String _database;
    private final String _user;
    private final String _password;

    public PostgresConnectionFactory(String host, int port, String database, String user, String password) {
        _host = host;
        _port = port;
        _database = database;
        _user = user;
        _password = password;
    }

    @Override
    public boolean IsConfigured() {
        return !IsBlank(_host) && !IsBlank(_database) && !IsBlank(_user);
    }

    @Override
    public Connection Open(String tenant) {
        if (tenant == null || !SchemaName.matcher(tenant).matches())
            throw new IllegalArgumentException("'" + tenant + "' is not a schema name: a tenant on PostgreSQL is [a-z_][a-z0-9_]*");
        Connection conn = Connect(tenant);
        try {
            ReadStringsTheStandardWay(conn);
            RequireTheSchema(conn, tenant);
            return conn;
        } catch (RuntimeException | SQLException e) {
            try { conn.close(); } catch (SQLException ignored) { /* the first failure is the one to report */ }
            throw e instanceof RuntimeException r ? r : new RuntimeException(e);
        }
    }

    private Connection Connect(String tenant) {
        Properties props = new Properties();
        props.setProperty("user", _user);
        if (_password != null && !_password.isEmpty()) props.setProperty("password", _password);
        props.setProperty("currentSchema", tenant);
        props.setProperty("ApplicationName", "billing-core");
        try {
            return DriverManager.getConnection("jdbc:postgresql://" + _host + ":" + _port + "/" + _database, props);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ReadStringsTheStandardWay(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("set standard_conforming_strings = on");
        }
    }

    private void RequireTheSchema(Connection conn, String tenant) throws SQLException {
        String current = conn.getSchema();
        if (!tenant.equals(current))
            throw new IllegalStateException("schema '" + tenant + "' is not in database '" + _database + "' on " + _host + ":" + _port
                    + ", or the role '" + _user + "' may not use it — a tenant's schema is made by prime-context's provisioning");
    }

    @Override
    public SqlDialect Dialect() {
        return SqlDialect.PostgreSql;
    }

    private static boolean IsBlank(String s) {
        return s == null || s.isBlank();
    }
}
