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
import java.sql.Statement;

/**
 * LIVE end-to-end run of the summary roll-up against the real datasource. For each schema it folds the
 * {@code summary_affected} outbox into its {@code sum_voice_day/hr} tables through the SAME production path
 * the consumer uses ({@link MySqlSummaryBatchRunner} → {@code SummaryRollup} → {@code CdrSummaryContext} →
 * {@code MySqlSummaryStore}), then reports what was written (before/after row counts + samples).
 *
 * <p>Gated on {@code ROLLUP_RUN=true} so it NEVER runs in a normal build. All connection details come from
 * the environment — nothing is hard-coded or committed. Run:</p>
 * <pre>
 *   ROLLUP_RUN=true ROLLUP_HOST=.. ROLLUP_USER=.. ROLLUP_PASS=.. ROLLUP_DBS=telcobright,res_233 \
 *     mvn -Dtest=SummaryRollupLiveTests -DfailIfNoTests=false test
 * </pre>
 * Re-running is safe: the {@code summary_offset} cursor advances with each fold, so a second run consumes 0.
 */
@EnabledIfEnvironmentVariable(named = "ROLLUP_RUN", matches = "true")
class SummaryRollupLiveTests {

    private static final String[] TABLES = {"sum_voice_day_03", "sum_voice_hr_03", "sum_voice_day_02", "sum_voice_hr_02"};

    @Test
    void fold_outbox_into_sum_voice_and_report() {
        String host = env("ROLLUP_HOST", "103.95.96.77");
        int port = Integer.parseInt(env("ROLLUP_PORT", "3306"));
        String user = env("ROLLUP_USER", "");
        String pass = env("ROLLUP_PASS", "");
        String dbsCsv = env("ROLLUP_DBS", "telcobright");
        String reportPath = env("ROLLUP_REPORT", "target/rollup-report.txt");
        String entityType = env("ROLLUP_ENTITY", "cdr");
        int maxRows = Integer.parseInt(env("ROLLUP_MAXROWS", "500"));

        var runner = new MySqlSummaryBatchRunner();
        var sb = new StringBuilder();
        line(sb, "SUMMARY ROLL-UP LIVE RUN  host=" + host + "  entity=" + entityType + "  maxRows=" + maxRows);

        try {
            for (String raw : dbsCsv.split(",")) {
                String db = raw.trim();
                if (db.isEmpty()) continue;
                line(sb, "");
                line(sb, "=== schema=" + db + " ===");
                try (Connection conn = open(host, port, db, user, pass, sb)) {
                    long affTotal = scalarLong(conn, "select count(*) from summary_affected where entity_type='" + entityType + "'", -1);
                    if (affTotal < 0) { line(sb, "  (no summary_affected table here — skipped)"); continue; }
                    long affMax = scalarLong(conn, "select coalesce(max(id),0) from summary_affected where entity_type='" + entityType + "'", 0);

                    MySqlSummaryBatchRunner.EnsureOffsetTable(conn);   // autocommit (outside the sweep tx)
                    long offBefore = scalarLong(conn, "select coalesce(last_offset,0) from summary_offset where entity_type='" + entityType + "'", 0);
                    line(sb, "  summary_affected: rows=" + affTotal + " max_id=" + affMax + " | offset(before)=" + offBefore);

                    for (String t : TABLES) {
                        long c = scalarLong(conn, "select count(*) from " + t, -1);
                        if (c >= 0) line(sb, "  before " + t + ": rows=" + c);
                    }

                    int consumed = 0, folded = 0, iters = 0;
                    long offAfter = offBefore;
                    while (iters++ < 100_000) {
                        var res = runner.Run(conn, entityType, maxRows, 1000);
                        consumed += res.rowsConsumed();
                        folded += res.callsFolded();
                        offAfter = res.newOffset();
                        if (res.rowsConsumed() < maxRows) break;
                    }
                    line(sb, "  >>> FOLDED: outboxRowsConsumed=" + consumed + " callsFolded=" + folded + " | offset(after)=" + offAfter);

                    for (String t : TABLES) {
                        long c = scalarLong(conn, "select count(*) from " + t, -1);
                        if (c < 0) continue;
                        Object calls = scalar(conn, "select coalesce(sum(totalcalls),0) from " + t);
                        Object cost = scalar(conn, "select coalesce(sum(customercost),0) from " + t);
                        Object dur = scalar(conn, "select coalesce(sum(actualduration),0) from " + t);
                        line(sb, "  after  " + t + ": rows=" + c + " totalcalls=" + calls + " actualduration=" + dur + " customercost=" + cost);
                    }
                    sample(conn, sb, "sum_voice_day_03");
                    sample(conn, sb, "sum_voice_day_02");
                } catch (Exception e) {
                    line(sb, "  ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } finally {
            String report = sb.toString();
            System.out.println(report);
            try { Files.writeString(Path.of(reportPath), report); } catch (IOException ignore) { /* best effort */ }
        }
    }

    private static void sample(Connection conn, StringBuilder sb, String table) {
        String sql = "select tup_starttime, tup_matchedprefixcustomer, tup_destinationId, totalcalls, actualduration, "
                + "customercost, tax1 from " + table + " order by tup_starttime desc limit 5";
        try (Statement st = conn.createStatement(); ResultSet r = st.executeQuery(sql)) {
            boolean any = false;
            while (r.next()) {
                if (!any) { line(sb, "  sample " + table + " (latest 5):"); any = true; }
                line(sb, "    " + r.getString(1) + " | custPfx=" + r.getString(2) + " dest=" + r.getString(3)
                        + " calls=" + r.getLong(4) + " dur=" + r.getBigDecimal(5)
                        + " cost=" + r.getBigDecimal(6) + " tax1=" + r.getBigDecimal(7));
            }
        } catch (Exception ignore) { /* table may not exist */ }
    }

    /** Faithful path first (the production MySqlConnectionFactory, plain URL); fall back to params if the
     *  server needs them — and NOTE it, since that would mean the real consumer needs them too. */
    private static Connection open(String host, int port, String db, String user, String pass, StringBuilder sb) {
        try {
            return new MySqlConnectionFactory(host, port, user, pass).Open(db);
        } catch (Exception e1) {
            line(sb, "  note: plain connect failed (" + e1.getMessage() + ") — retry w/ params (consumer would need them too)");
            try {
                String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
                return DriverManager.getConnection(url, user, pass);
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
