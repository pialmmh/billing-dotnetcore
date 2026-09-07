package com.telcobright.billing.mediation.summary;

import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.data.MySqlSummaryBatchRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DEMO (gated on {@code ROLLUP_RUN=true}): append a forward daily partition to the target schema's
 * {@code sum_voice_*} tables (needed because the inherited partition map is frozen at 2025-08-16, so a
 * current-dated summary has no partition), then fold its {@code summary_affected} outbox and print the
 * written rows. Defaults to {@code res_233_2} (empty sum_voice tables → no legacy contamination). ADD
 * PARTITION is append-only and reversible (DROP PARTITION); it never rewrites existing partitions/data.
 */
@EnabledIfEnvironmentVariable(named = "ROLLUP_RUN", matches = "true")
class SummaryRollupFixAndFoldTests {

    private static final String[] TABLES = {"sum_voice_day_03", "sum_voice_hr_03", "sum_voice_day_02", "sum_voice_hr_02"};

    @Test
    void add_forward_partition_then_fold() {
        String host = env("ROLLUP_HOST", "127.0.0.1");   // public repo: no real host defaults — pass ROLLUP_HOST
        int port = Integer.parseInt(env("ROLLUP_PORT", "3306"));
        String user = env("ROLLUP_USER", "");
        String pass = env("ROLLUP_PASS", "");
        String db = env("ROLLUP_FIX_DB", "res_233_2");
        String boundary = env("ROLLUP_PART_BOUNDARY", "2026-07-17");   // VALUES LESS THAN this date
        String entityType = env("ROLLUP_ENTITY", "cdr");
        int maxRows = Integer.parseInt(env("ROLLUP_MAXROWS", "500"));
        String reportPath = env("ROLLUP_FIX_REPORT", "target/rollup-fixfold.txt");

        String pname = "p_ext_" + boundary.replace("-", "");
        var runner = new MySqlSummaryBatchRunner();
        var sb = new StringBuilder();
        line(sb, "FIX+FOLD  host=" + host + " db=" + db + "  add partition " + pname + " VALUES LESS THAN ('" + boundary + "')");

        try (Connection conn = open(host, port, db, user, pass)) {
            for (String t : TABLES) {
                String sql = "alter table " + t + " add partition (partition " + pname + " values less than ('" + boundary + "'))";
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate(sql);
                    line(sb, "  +partition " + t + ": OK");
                } catch (SQLException e) {
                    line(sb, "  +partition " + t + ": skipped (" + e.getMessage() + ")");
                }
            }

            MySqlSummaryBatchRunner.EnsureOffsetTable(conn);
            int consumed = 0, folded = 0, iters = 0;
            long off = 0;
            while (iters++ < 100_000) {
                var res = runner.Run(conn, entityType, maxRows, 1000);
                consumed += res.rowsConsumed();
                folded += res.callsFolded();
                off = res.newOffset();
                if (res.rowsConsumed() < maxRows) break;
            }
            line(sb, "  >>> FOLDED: outboxRowsConsumed=" + consumed + " callsFolded=" + folded + " offset(after)=" + off);

            for (String t : TABLES) {
                long c = scalarLong(conn, "select count(*) from " + t, -1);
                if (c < 0) continue;
                Object calls = scalar(conn, "select coalesce(sum(totalcalls),0) from " + t);
                Object cost = scalar(conn, "select coalesce(sum(customercost),0) from " + t);
                line(sb, "  " + t + ": rows=" + c + " totalcalls=" + calls + " customercost=" + cost);
            }
            dump(conn, sb, "sum_voice_day_03");
            dump(conn, sb, "sum_voice_hr_03");
        } catch (Exception e) {
            line(sb, "  ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            String rep = sb.toString();
            System.out.println(rep);
            try { Files.writeString(Path.of(reportPath), rep); } catch (IOException ignore) { /* best effort */ }
        }
    }

    private static void dump(Connection conn, StringBuilder sb, String table) {
        String sql = "select id, tup_starttime, tup_switchid, tup_inpartnerid, tup_outpartnerid, tup_matchedprefixcustomer, "
                + "tup_customerrate, totalcalls, actualduration, roundedduration, customercost, tax1, tup_customercurrency "
                + "from " + table + " order by id";
        try (Statement st = conn.createStatement(); ResultSet r = st.executeQuery(sql)) {
            boolean any = false;
            while (r.next()) {
                if (!any) { line(sb, "  ROWS WRITTEN in " + table + ":"); any = true; }
                line(sb, "    id=" + r.getLong("id") + " start=" + r.getString("tup_starttime")
                        + " sw=" + r.getInt("tup_switchid") + " inP=" + r.getInt("tup_inpartnerid") + " outP=" + r.getInt("tup_outpartnerid")
                        + " custPfx=" + r.getString("tup_matchedprefixcustomer") + " rate=" + r.getBigDecimal("tup_customerrate")
                        + " calls=" + r.getLong("totalcalls") + " actDur=" + r.getBigDecimal("actualduration")
                        + " rndDur=" + r.getBigDecimal("roundedduration") + " cost=" + r.getBigDecimal("customercost")
                        + " tax1=" + r.getBigDecimal("tax1") + " cur=" + r.getString("tup_customercurrency"));
            }
            if (!any) line(sb, "  (no rows in " + table + ")");
        } catch (Exception e) {
            line(sb, "  dump " + table + " ERROR: " + e.getMessage());
        }
    }

    private static Connection open(String host, int port, String db, String user, String pass) {
        try {
            return new MySqlConnectionFactory(host, port, user, pass).Open(db);
        } catch (Exception e1) {
            try {
                return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC", user, pass);
            } catch (Exception e2) {
                throw new RuntimeException(e2);
            }
        }
    }

    private static String env(String k, String def) { String v = System.getenv(k); return (v == null || v.isBlank()) ? def : v; }
    private static void line(StringBuilder sb, String s) { sb.append(s).append('\n'); }
    private static Object scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) { return r.next() ? r.getObject(1) : null; }
        catch (Exception e) { return null; }
    }
    private static long scalarLong(Connection c, String sql, long onErr) {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) { return r.next() ? r.getLong(1) : onErr; }
        catch (Exception e) { return onErr; }
    }
}
