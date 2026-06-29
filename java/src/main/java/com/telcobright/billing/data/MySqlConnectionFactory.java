package com.telcobright.billing.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens a {@link java.sql.Connection} to a tenant's schema on the configured datasource — the write
 * target for the batch/post-call slice. Host/port come from the profile's {@code billing.datasource} block;
 * the username/password are supplied here (eventually resolved from OpenBao via the datasource secret-ref;
 * for the dev simulation they come from configuration). One connection per batch — the caller
 * ({@link MySqlCdrBatchRunner}) owns the single transaction.
 *
 * <p>FAITHFUL-PORT NOTE (MySqlConnector -&gt; JDBC): the C# {@code MySqlConnection} over a connection string
 * becomes a {@code DriverManager.getConnection(jdbc-url, user, password)}. mysql-connector-j 8.x auto-registers
 * its driver, so no explicit {@code Class.forName} is needed. The C# connection string set only
 * Server/Port/Database/User/Password (no extra options), so the JDBC URL carries no query parameters.</p>
 */
public final class MySqlConnectionFactory {
    private final String _host;
    private final int _port;
    private final String _user;
    private final String _password;

    public MySqlConnectionFactory(String host, int port, String user, String password) {
        _host = host;
        _port = port;
        _user = user;
        _password = password;
    }

    /** True once a host + username are present (so the batch RPC can refuse cleanly otherwise). */
    public boolean IsConfigured() {
        return !(_host == null || _host.isBlank()) && !(_user == null || _user.isBlank());
    }

    /** Open a connection to the given schema (the tenant dbName) on the datasource. */
    public Connection Open(String database) {
        var url = "jdbc:mysql://" + _host + ":" + _port + "/" + database;
        try {
            return DriverManager.getConnection(url, _user, _password);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
