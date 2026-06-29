// Faithful port of tests/Billing.Tests/CdrWriterTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (CdrWriter) per RULE T0.
// The integration test connects to the project's local lxc MySQL and SKIPS (Assumptions) when it is down,
// so it never hard-fails the build (RULE T5). MySqlConnector -> java.sql/DriverManager (mysql-connector-j).
package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

/**
 * The cdr row write: the ported ICacheble&lt;cdr&gt; insert (104 cols) — column/value arity parity (a
 * hand-port guard) and, against local lxc MySQL, a real segmented insert with a null float leg as NULL.
 */
class CdrWriterTests {

    private static cdr SampleCdr(String billId, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1; c.IdCall = 1001; c.SequenceNumber = 1; c.ServiceGroup = 10;
        c.IncomingRoute = "in"; c.OutgoingRoute = "out"; c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = "8801712345678"; c.OriginatingCallingNumber = "8801999000111";
        c.DurationSec = BigDecimal.valueOf(60); c.RoundedDuration = BigDecimal.valueOf(60); c.Duration1 = BigDecimal.valueOf(60);
        c.StartTime = when; c.EndTime = when; c.AnswerTime = when; c.SignalingStartTime = when;
        c.ChargingStatus = 1; c.CountryCode = "880"; c.InPartnerId = 5; c.UniqueBillId = billId;
        c.Category = 1; c.SubCategory = 1;   // PDD left null (Float) on purpose -> proves null rendering
        return c;
    }

    @Test
    void Insert_columns_and_values_have_the_same_arity() {
        var columns = cdr.ExtInsertColumns.split(",", -1).length;
        var tuple = SampleCdr("uid-1", LocalDateTime.of(2026, 6, 19, 14, 30, 0)).GetExtInsertValues().toString();
        var fields = tuple.substring(1, tuple.length() - 1).split(",", -1).length;   // strip the wrapping ( )

        assertEquals(104, columns);
        assertEquals(columns, fields);   // header and value tuple must line up
    }

    // ---- integration (skips if local mysql is unreachable) ----
    private static final String ServerUrl =
            "jdbc:mysql://127.0.0.1:3306/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    private static final String User = "root";
    private static final String Password = "123456";
    private static final String Db = "billing_cdr_test";

    private static final class ConnExecutor implements ISqlExecutor {
        private final Connection _c;
        ConnExecutor(Connection c) { _c = c; }
        @Override public int ExecuteNonQuery(String sql) {
            try (Statement cmd = _c.createStatement()) { return cmd.executeUpdate(sql); }
            catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    private static Connection TryOpen() {
        try { return DriverManager.getConnection(ServerUrl, User, Password); } catch (Exception e) { return null; }
    }

    private static void Exec(Connection c, String sql) throws SQLException {
        try (Statement cmd = c.createStatement()) { cmd.execute(sql); }
    }

    private static Object Scalar(Connection c, String sql) throws SQLException {
        try (Statement cmd = c.createStatement(); ResultSet rs = cmd.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    @Test
    void Writes_cdr_rows_to_mysql() throws Exception {
        Connection conn = TryOpen();
        assumeTrue(conn != null, "local MySQL not reachable -> skipping");
        try (conn) {
            Exec(conn, "create database if not exists " + Db);
            Exec(conn, "use " + Db);
            Exec(conn, "drop table if exists cdr");
            // permissive schema (all columns TEXT NULL) — a column-count + value-formatting smoke test of the
            // 104-col insert; the real typed schema is the operator DB. TEXT keeps the row within InnoDB limits.
            var cols = Arrays.stream(cdr.ExtInsertColumns.split(",", -1))
                    .map(c -> "`" + c + "` text null")
                    .collect(Collectors.joining(", "));
            Exec(conn, "create table cdr (" + cols + ")");

            var when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
            var written = CdrWriter.Write(new ConnExecutor(conn),
                    List.of(SampleCdr("uid-1", when), SampleCdr("uid-2", when)));

            assertEquals(2, written);
            assertEquals(2L, ((Number) Scalar(conn, "select count(*) from cdr")).longValue());
            assertEquals("8801712345678",
                    Scalar(conn, "select TerminatingCalledNumber from cdr where UniqueBillId='uid-1'").toString());
            assertNull(Scalar(conn, "select PDD from cdr where UniqueBillId='uid-1'"));   // null float -> SQL NULL
        }
    }
}
