package com.telcobright.billing.beans;

import com.telcobright.billing.data.MySqlSummaryBatchRunner;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryRollupOptions;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The summary roll-up consumer's interaction with MySQL across many sweeps. Production finding (2026-10-07): every
 * 2-second sweep ran {@code CREATE TABLE IF NOT EXISTS summary_offset}. MySQL binlogs that statement even when the
 * table exists, and every binlogged DDL reaches the CDC pipeline as a config change — so each sweep became one
 * config-manager reload and one config event (0.2/min before billing-core started, ~29/min after). The sweeps run
 * here against a fake JDBC schema that records every statement.
 */
class SummaryRollupConsumerTests {

    /** One tenant schema behind a recording JDBC fake: an empty outbox, and a summary_offset table that may exist. */
    static final class FakeSchema {
        final String name;
        boolean offsetTableExists;
        int failExistenceChecks;   // the next N existence checks fail like a dropped connection
        final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        FakeSchema(String name, boolean offsetTableExists) {
            this.name = name;
            this.offsetTableExists = offsetTableExists;
        }

        long Count(String fragment) {
            return statements.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(fragment)).count();
        }

        Connection Open() {
            return Proxy(Connection.class, (method, args) -> switch (method) {
                case "createStatement" -> NewStatement();
                case "prepareStatement" -> NewPrepared((String) args[0]);
                case "getCatalog" -> name;
                case "setAutoCommit", "commit", "rollback", "close" -> null;
                case "getAutoCommit" -> true;
                case "isClosed" -> false;
                default -> throw new UnsupportedOperationException("Connection." + method);
            });
        }

        private Statement NewStatement() {
            return Proxy(Statement.class, (method, args) -> switch (method) {
                case "executeUpdate", "execute" -> {
                    String sql = (String) args[0];
                    statements.add(sql);
                    if (sql.toLowerCase(Locale.ROOT).startsWith("create table")) offsetTableExists = true;
                    yield method.equals("execute") ? (Object) false : (Object) 0;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException("Statement." + method);
            });
        }

        private PreparedStatement NewPrepared(String sql) {
            return Proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setString", "setLong", "setInt", "close" -> null;
                case "executeQuery" -> {
                    statements.add(sql);
                    yield Result(sql);
                }
                case "executeUpdate" -> {
                    statements.add(sql);
                    yield 1;
                }
                default -> throw new UnsupportedOperationException("PreparedStatement." + method);
            });
        }

        private ResultSet Result(String sql) throws SQLException {
            String s = sql.toLowerCase(Locale.ROOT);
            if (s.contains("information_schema.tables")) {
                if (failExistenceChecks > 0) {
                    failExistenceChecks--;
                    throw new SQLException("Communications link failure");
                }
                return Rows(List.of(offsetTableExists ? 1L : 0L));
            }
            if (s.contains("get_lock(") || s.contains("release_lock(")) return Rows(List.of(1L));
            return Rows(List.of());   // summary_offset cursor + summary_affected page: both empty
        }

        /** A ResultSet over single-column rows. */
        private static ResultSet Rows(List<Long> values) {
            int[] at = {-1};
            return Proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> ++at[0] < values.size();
                case "getInt" -> values.get(at[0]).intValue();
                case "getLong" -> values.get(at[0]);
                case "close" -> null;
                default -> throw new UnsupportedOperationException("ResultSet." + method);
            });
        }
    }

    interface Handler {
        Object Handle(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    static <T> T Proxy(Class<T> type, Handler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, m, args) -> m.getName().equals("toString") ? type.getSimpleName() + "-fake" : h.Handle(m.getName(), args));
    }

    /** A loaded registry whose root tenant indexes the given schemas. */
    static final class Registry implements ITenantRegistry {
        final Tenant root = new Tenant();
        final AtomicInteger reads = new AtomicInteger();

        Registry(String... schemas) {
            root.Name = schemas[0];
            root.DbName = schemas[0];
            for (String s : schemas) {
                Tenant t = s.equals(schemas[0]) ? root : new Tenant();
                t.Name = s;
                t.DbName = s;
                root.Index.put(s, t);
            }
        }

        @Override public boolean IsLoaded() { reads.incrementAndGet(); return true; }
        @Override public Tenant FindByDbName(String dbName) { return root.Index.get(dbName); }
        @Override public List<Tenant> AncestorChain(String dbName) { return List.of(root); }
        @Override public Collection<Tenant> Roots() { reads.incrementAndGet(); return List.of(root); }
    }

    static SummaryRollupOptions Options(boolean enabled) {
        var o = new SummaryRollupOptions();
        o.Enabled = enabled;
        return o;
    }

    static SummaryRollupConsumer ConsumerOver(Registry registry, Map<String, FakeSchema> schemas) {
        return new SummaryRollupConsumer(registry, () -> true, db -> schemas.get(db).Open(),
                new MySqlSummaryBatchRunner(), Options(true));
    }

    // ─────────────────────────── the DDL storm ───────────────────────────

    @Test
    void repeated_sweeps_issue_no_ddl_when_the_offset_table_exists() {
        var schema = new FakeSchema("sms_tenant", true);
        var consumer = ConsumerOver(new Registry("sms_tenant"), Map.of("sms_tenant", schema));

        for (int i = 0; i < 30; i++) consumer.SweepAllTenants();   // one minute of 2-second polls

        assertEquals(30, schema.Count("get_lock("), "every sweep really ran");
        assertEquals(0, schema.Count("create table"), "no DDL — nothing for the CDC pipeline to turn into a config event");
        assertEquals(1, schema.Count("information_schema.tables"), "existence checked once, not per sweep");
    }

    @Test
    void a_missing_offset_table_is_created_exactly_once() {
        var schema = new FakeSchema("sms_tenant", false);
        var consumer = ConsumerOver(new Registry("sms_tenant"), Map.of("sms_tenant", schema));

        for (int i = 0; i < 30; i++) consumer.SweepAllTenants();

        assertEquals(1, schema.Count("create table if not exists summary_offset"), "created once, on the first sweep");
        assertEquals(30, schema.Count("get_lock("));
    }

    @Test
    void each_schema_is_checked_once() {
        var root = new FakeSchema("sms_tenant", true);
        var reseller = new FakeSchema("res_1", false);
        var consumer = ConsumerOver(new Registry("sms_tenant", "res_1"), Map.of("sms_tenant", root, "res_1", reseller));

        for (int i = 0; i < 30; i++) consumer.SweepAllTenants();

        assertEquals(1, root.Count("information_schema.tables"));
        assertEquals(0, root.Count("create table"));
        assertEquals(1, reseller.Count("information_schema.tables"));
        assertEquals(1, reseller.Count("create table"));
    }

    @Test
    void a_failed_existence_check_is_retried_next_sweep_without_any_ddl() {
        var schema = new FakeSchema("sms_tenant", true);
        schema.failExistenceChecks = 2;   // MySQL unreachable for the first two sweeps
        var consumer = ConsumerOver(new Registry("sms_tenant"), Map.of("sms_tenant", schema));

        for (int i = 0; i < 30; i++) consumer.SweepAllTenants();

        assertEquals(3, schema.Count("information_schema.tables"), "2 failed checks, then 1 that sticks");
        assertEquals(0, schema.Count("create table"));
        assertEquals(28, schema.Count("get_lock("), "a sweep whose check failed is skipped, the rest run");
    }

    // ─────────────────────────── roll-up paused ───────────────────────────

    @Test
    void a_disabled_rollup_never_opens_a_connection_or_starts_its_thread() throws Exception {
        var registry = new Registry("sms_tenant");
        var opened = new AtomicInteger();
        var consumer = new SummaryRollupConsumer(registry, () -> true, db -> {
            opened.incrementAndGet();
            throw new AssertionError("a paused roll-up must not open " + db);
        }, new MySqlSummaryBatchRunner(), Options(false));

        consumer.onStart(null);
        Thread.sleep(300);

        assertEquals(0, opened.get(), "no connection");
        assertEquals(0, registry.reads.get(), "not even the tenant registry is consulted");
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("summary-rollup-consumer") && t.isAlive()), "no consumer thread");
        consumer.onStop();
    }
}
