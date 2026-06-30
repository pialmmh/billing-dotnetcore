package com.telcobright.billing.data;

import com.telcobright.billing.mediation.summary.ISummaryStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The live {@link ISummaryStore} over MySQL (JDBC) — the batch's tx-bound SQL executor. It runs on a
 * caller-supplied connection so it shares the single per-call connection the atomic write uses;
 * {@code ExecuteNonQuery} runs the cdr / chargeable / summary-outbox INSERTs in that transaction.
 *
 * <p>FAITHFUL-PORT NOTE (MySqlConnector -&gt; JDBC): the C# optional {@code MySqlTransaction? tx} param is
 * dropped — JDBC has no separate transaction object; statements created from the connection already run in
 * the connection's current transaction (the batch runner owns it via setAutoCommit(false)).</p>
 */
public final class MySqlSummaryStore implements ISummaryStore {
    private final Connection _conn;

    public MySqlSummaryStore(Connection conn) {
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
}
