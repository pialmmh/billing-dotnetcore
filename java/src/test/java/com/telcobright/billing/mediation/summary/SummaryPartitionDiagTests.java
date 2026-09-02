package com.telcobright.billing.mediation.summary;

import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * DIAGNOSTIC (gated on {@code ROLLUP_RUN=true}) for the "Table has no partition for value from column_list"
 * failure: dumps the {@code sum_voice_*} partition scheme (method / expression / last boundaries) and decodes
 * each pending {@code summary_affected} row to show the exact {@code StartTime} / day+hour bucket the insert
 * needs a partition for. Read-only — no writes.
 */
@EnabledIfEnvironmentVariable(named = "ROLLUP_RUN", matches = "true")
class SummaryPartitionDiagTests {

    private static final String[] TABLES = {"sum_voice_day_03", "sum_voice_hr_03", "sum_voice_day_02", "sum_voice_hr_02"};

    @Test
    void diagnose_partitions_and_pending_rows() {
        String host = env("ROLLUP_HOST", "127.0.0.1");   // public repo: no real host defaults — pass ROLLUP_HOST
        int port = Integer.parseInt(env("ROLLUP_PORT", "3306"));
        String user = env("ROLLUP_USER", "");
        String pass = env("ROLLUP_PASS", "");
        String dbsCsv = env("ROLLUP_DBS", "telcobright");
        String reportPath = env("ROLLUP_DIAG_REPORT", "target/rollup-diag.txt");
        String entityType = env("ROLLUP_ENTITY", "cdr");

        var sb = new StringBuilder();
        line(sb, "PARTITION DIAG  host=" + host);
        try {
            for (String raw : dbsCsv.split(",")) {
                String db = raw.trim();
                if (db.isEmpty()) continue;
                line(sb, "");
                line(sb, "=== schema=" + db + " ===");
                try (Connection conn = open(host, port, db, user, pass)) {
                    dumpPending(conn, sb, entityType);
                    for (String t : TABLES) dumpPartitions(conn, sb, db, t);
                } catch (Exception e) {
                    line(sb, "  ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } finally {
            String rep = sb.toString();
            System.out.println(rep);
            try { Files.writeString(Path.of(reportPath), rep); } catch (IOException ignore) { /* best effort */ }
        }
    }

    private static void dumpPending(Connection conn, StringBuilder sb, String entityType) {
        String sql = "select id, op, data from summary_affected where entity_type='" + entityType + "' order by id";
        try (Statement st = conn.createStatement(); ResultSet r = st.executeQuery(sql)) {
            int n = 0;
            while (r.next()) {
                long id = r.getLong("id");
                String op = r.getString("op");
                List<Entry> entries = SummaryOutboxWriter.Decode(r.getString("data"));
                line(sb, "  outbox id=" + id + " op=" + op + " entries=" + entries.size());
                int shown = 0;
                for (Entry e : entries) {
                    var cust = e.Customer();
                    var start = e.Cdr() != null ? e.Cdr().StartTime : null;
                    Integer sg = cust != null ? cust.servicegroup : null;
                    line(sb, "    StartTime=" + start + " sg=" + sg
                            + (start != null ? "  -> dayBucket=" + start.toLocalDate().atStartOfDay()
                                + " hrBucket=" + start.truncatedTo(ChronoUnit.HOURS) : ""));
                    if (++shown >= 5) { line(sb, "    ..."); break; }
                }
                if (++n >= 10) { line(sb, "  ..."); break; }
            }
            if (n == 0) line(sb, "  (no pending outbox rows)");
        } catch (Exception e) {
            line(sb, "  pending dump ERROR: " + e.getMessage());
        }
    }

    private static void dumpPartitions(Connection conn, StringBuilder sb, String db, String table) {
        String sql = "select partition_name, partition_method, partition_expression, partition_description, table_rows "
                + "from information_schema.partitions where table_schema='" + db + "' and table_name='" + table + "' "
                + "and partition_name is not null order by partition_ordinal_position";
        try (Statement st = conn.createStatement(); ResultSet r = st.executeQuery(sql)) {
            List<String> parts = new ArrayList<>();
            String method = null, expr = null;
            while (r.next()) {
                method = r.getString("partition_method");
                expr = r.getString("partition_expression");
                parts.add(r.getString("partition_name") + "→VALUES LESS THAN (" + r.getString("partition_description")
                        + ") rows=" + r.getLong("table_rows"));
            }
            if (parts.isEmpty()) { line(sb, "  " + table + ": NOT partitioned"); return; }
            line(sb, "  " + table + ": " + method + "(" + expr + ")  parts=" + parts.size());
            int from = Math.max(0, parts.size() - 4);
            if (from > 0) line(sb, "      ...(" + from + " earlier)");
            for (int i = from; i < parts.size(); i++) line(sb, "      " + parts.get(i));
        } catch (Exception e) {
            line(sb, "  " + table + ": partition query ERROR: " + e.getMessage());
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
}
