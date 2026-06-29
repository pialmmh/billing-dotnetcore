package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.summary.CountingAutoIncrementManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * INTEGRATION TEST against local lxc MySQL: ChargeableWriter's batched insert (the legacy
 * acc_chargeable ExtInsertColumns + GetExtInsertValues) is accepted by a real acc_chargeable table — ids
 * are assigned, both legs land, and a null tax leg stores SQL NULL. Skips if mysql is unreachable.
 *
 * <p>FAITHFUL-PORT NOTE (xUnit -&gt; JUnit 5): the per-test {@code using var conn = TryOpen(); if (conn is
 * null) return;} skip becomes a {@code @BeforeEach} that opens the connection and
 * {@code assumeTrue(conn != null, ...)} so a missing DB ABORTS (skips), never fails.</p>
 */
class ChargeableWriterMySqlIntegrationTests {
    private static final String ServerUrl = "jdbc:mysql://127.0.0.1:3306/";
    private static final String User = "root";
    private static final String Password = "123456";
    private static final String Db = "billing_chargeable_test";

    private Connection conn;

    /** Wraps the per-call connection so ChargeableWriter writes through the real DB (legacy ConnExecutor). */
    private static final class ConnExecutor implements ISqlExecutor {
        private final Connection _c;

        ConnExecutor(Connection c) {
            _c = c;
        }

        @Override
        public int ExecuteNonQuery(String sql) {
            try (Statement cmd = _c.createStatement()) {
                return cmd.executeUpdate(sql);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Connection tryOpen() {
        try {
            return DriverManager.getConnection(ServerUrl, User, Password);
        } catch (Exception e) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        conn = tryOpen();
        assumeTrue(conn != null, "local MySQL not reachable — skipping");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) conn.close();
    }

    private static void Exec(Connection c, String sql) {
        try (Statement cmd = c.createStatement()) {
            cmd.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object Scalar(Connection c, String sql) {
        try (Statement cmd = c.createStatement();
             ResultSet rs = cmd.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void Writes_chargeable_rows_to_mysql() {
        Exec(conn, "create database if not exists " + Db);
        Exec(conn, "use " + Db);
        Exec(conn, "drop table if exists acc_chargeable");
        Exec(conn, """
                create table acc_chargeable(
                    id bigint primary key, uniqueBillId varchar(64), idEvent bigint, transactionTime datetime,
                    assignedDirection tinyint null, description varchar(255), glAccountId bigint, servicegroup int,
                    servicefamily int, ProductId bigint, idBilledUom varchar(16), idQuantityUom varchar(16),
                    BilledAmount decimal(18,6), Quantity decimal(18,6), unitPriceOrCharge decimal(18,6), Prefix varchar(32),
                    RateId bigint, TaxAmount1 decimal(18,6) null, TaxAmount2 decimal(18,6) null, TaxAmount3 decimal(18,6) null,
                    VatAmount1 decimal(18,6) null, VatAmount2 decimal(18,6) null, VatAmount3 decimal(18,6) null,
                    OtherAmount1 decimal(18,6) null, OtherAmount2 decimal(18,6) null, OtherAmount3 decimal(18,6) null,
                    OtherDecAmount1 decimal(18,6) null, OtherDecAmount2 decimal(18,6) null, OtherDecAmount3 decimal(18,6) null,
                    createdByJob bigint null, changedByJob bigint null, idBillingrule int, jsonDetail text)""");

        var when = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        var chargeables = new ArrayList<acc_chargeable>();

        acc_chargeable c0 = new acc_chargeable();
        c0.servicegroup = 10;
        c0.servicefamily = 10;
        c0.BilledAmount = new BigDecimal("1.0");
        c0.TaxAmount1 = new BigDecimal("0.5");
        c0.Prefix = "1712";
        c0.unitPriceOrCharge = new BigDecimal("1.0");
        c0.idBilledUom = "BDT";
        c0.transactionTime = when;
        c0.uniqueBillId = "uid-1";
        c0.Quantity = BigDecimal.valueOf(60);
        chargeables.add(c0);

        acc_chargeable c1 = new acc_chargeable();
        c1.servicegroup = 1;
        c1.servicefamily = 1;
        c1.BilledAmount = new BigDecimal("2.0");
        c1.Prefix = "1712";
        c1.assignedDirection = (byte) 2;
        c1.unitPriceOrCharge = new BigDecimal("2.0");
        c1.idBilledUom = "BDT";
        c1.transactionTime = when;
        c1.uniqueBillId = "uid-1";
        c1.Quantity = BigDecimal.valueOf(60);
        chargeables.add(c1);

        var written = ChargeableWriter.Write(new ConnExecutor(conn), chargeables, new CountingAutoIncrementManager(1));

        assertEquals(2, written);                 // both rows inserted in one batched statement
        assertEquals(1L, chargeables.get(0).id);  // ids assigned from the counter
        assertEquals(2L, chargeables.get(1).id);
        assertEquals(2L, ((Number) Scalar(conn, "select count(*) from acc_chargeable")).longValue());
        assertEquals(0, new BigDecimal("0.5").compareTo(
                (BigDecimal) Scalar(conn, "select TaxAmount1 from acc_chargeable where id=1")));
        assertNull(Scalar(conn, "select TaxAmount1 from acc_chargeable where id=2"));   // null leg → SQL NULL
    }
}
