package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.sql.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The batch's tx-bound SQL executor on PostgreSQL: the same one statement at a time on the batch's one connection as
 * {@link MySqlExecutor}; it only tells the writers that their literals and their {@code cdr} columns are
 * PostgreSQL's ({@link #Dialect()}).
 */
public final class PostgresExecutor implements ISqlExecutor {
    private final Connection _conn;

    public PostgresExecutor(Connection conn) {
        _conn = conn;
    }

    @Override
    public int ExecuteNonQuery(String sql) {
        try (Statement st = _conn.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SqlDialect Dialect() {
        return SqlDialect.PostgreSql;
    }
}
