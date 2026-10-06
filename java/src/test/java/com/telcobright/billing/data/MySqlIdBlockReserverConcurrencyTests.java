package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INTEGRATION TEST: proves the reservation statement is atomic against a REAL MySQL 5.7 server, using real
 * concurrent connections and the server's own row lock — not the {@code synchronized} in-memory fake the unit
 * tests use.
 *
 * <p>This test exists because production correctness rests on the DATABASE, not on Java: every allocator
 * in every billing process reserves through {@code update autoincrementcounter set value =
 * last_insert_id(value + n) where tableName = ?}, and the claim is that two connections executing that
 * concurrently are handed DISJOINT ranges. A single-JVM synchronized fake cannot prove that.</p>
 *
 * <p>Runs against a DISPOSABLE counter row ({@link #TestTable}) and never touches the real
 * {@code acc_chargeable} row. Skips unless {@code -Dbilling.it.host} is supplied, so a normal build and CI
 * never reach a live database.</p>
 */
class MySqlIdBlockReserverConcurrencyTests {

    /** A counter key that belongs to no real table; created and dropped by this test. */
    private static final String TestTable = "__alloc_selftest__";

    private static final int Threads = 8;
    private static final int ReservationsPerThread = 60;
    private static final int BlockSize = 500;

    private static MySqlConnectionFactory factory;
    private static String schema;

    @BeforeAll
    static void setUp() throws Exception {
        String host = System.getProperty("billing.it.host");
        assumeTrue(host != null && !host.isBlank(),
                "set -Dbilling.it.host/-Dbilling.it.user/-Dbilling.it.password/-Dbilling.it.schema to run");
        int port = Integer.parseInt(System.getProperty("billing.it.port", "3306"));
        String user = System.getProperty("billing.it.user");
        String password = System.getProperty("billing.it.password");
        schema = System.getProperty("billing.it.schema");
        factory = new MySqlConnectionFactory(host, port, user, password);

        try (Connection conn = factory.Open(schema); Statement st = conn.createStatement()) {
            st.executeUpdate("delete from " + MySqlIdBlockReserver.CounterTable
                    + " where tableName = '" + TestTable + "'");
            st.executeUpdate("insert into " + MySqlIdBlockReserver.CounterTable
                    + " (tableName, value) values ('" + TestTable + "', 0)");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (factory == null) return;
        try (Connection conn = factory.Open(schema); Statement st = conn.createStatement()) {
            st.executeUpdate("delete from " + MySqlIdBlockReserver.CounterTable
                    + " where tableName = '" + TestTable + "'");
        }
    }

    private record Range(long first, long last) {}

    @Test
    @DisplayName("concurrent reservations on REAL MySQL return disjoint, gapless, contiguous ranges")
    void ConcurrentReservationsAreDisjoint() throws Exception {
        // Each ReserveBlock call opens its OWN connection, so these are genuinely separate DB sessions
        // contending on one row - exactly the two-billing-instance case.
        var reserver = new MySqlIdBlockReserver(factory, schema);
        // Read the counter FIRST and assert the tiling relative to it: these tests share one disposable row,
        // so the starting value depends on execution order and must never be assumed to be zero.
        long base = ReadCounter();
        var pool = Executors.newFixedThreadPool(Threads);
        var start = new CountDownLatch(1);
        var ranges = Collections.synchronizedList(new ArrayList<Range>());
        var failures = Collections.synchronizedList(new ArrayList<String>());

        for (int t = 0; t < Threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ReservationsPerThread; i++) {
                        long first = reserver.ReserveBlock(TestTable, BlockSize);
                        ranges.add(new Range(first, first + BlockSize - 1));
                    }
                } catch (Exception e) {
                    failures.add(e.toString());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(180, TimeUnit.SECONDS), "reservations did not finish");
        assertTrue(failures.isEmpty(), "reservation errors: " + failures);

        int expected = Threads * ReservationsPerThread;
        assertEquals(expected, ranges.size());

        // No two ranges may overlap, and together they must tile [1 .. expected*BlockSize] exactly:
        // gapless proves nothing was double-counted, disjoint proves nothing was double-issued.
        var sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong(Range::first));
        long cursor = base;
        for (Range r : sorted) {
            assertEquals(cursor + 1, r.first(),
                    "ranges must tile with no overlap and no gap; got " + r.first() + " after " + cursor);
            assertEquals(r.first() + BlockSize - 1, r.last());
            cursor = r.last();
        }
        assertEquals(base + (long) expected * BlockSize, cursor);

        // and the durable counter must agree with the last id handed out
        assertEquals(cursor, ReadCounter(), "counter must equal the highest id reserved");
    }

    @Test
    @DisplayName("SeedAtLeast raises the counter and never lowers it")
    void SeedAtLeastIsMonotonic() throws Exception {
        var reserver = new MySqlIdBlockReserver(factory, schema);
        long raised = reserver.SeedAtLeast(TestTable, 5_000_000L);
        assertEquals(5_000_000L, raised);

        long unchanged = reserver.SeedAtLeast(TestTable, 42L);      // a lower floor must be ignored
        assertEquals(5_000_000L, unchanged, "greatest() must refuse to move the counter down");

        long first = reserver.ReserveBlock(TestTable, 10);
        assertEquals(5_000_001L, first, "the next block must start above the seeded value");
    }

    private static long ReadCounter() throws SQLException {
        try (Connection conn = factory.Open(schema);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("select value from " + MySqlIdBlockReserver.CounterTable
                     + " where tableName = '" + TestTable + "'")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
