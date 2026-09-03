package com.telcobright.billing.mediation.summary;

import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * DIAGNOSTIC (gated {@code ROLLUP_RUN=true}, read-only): decode the {@code summary_affected} blob and
 * reflectively dump EVERY field of the cdr + each chargeable leg, so we can see whether a 0/empty summary
 * column reflects a genuinely null source field or a mapping miss. Non-null fields are printed; null/empty
 * ones are listed compactly.
 */
@EnabledIfEnvironmentVariable(named = "ROLLUP_RUN", matches = "true")
class SummaryCdrDumpTests {

    @Test
    void dump_decoded_cdr_and_chargeables() {
        String host = env("ROLLUP_HOST", "127.0.0.1");   // public repo: no real host defaults — pass ROLLUP_HOST
        int port = Integer.parseInt(env("ROLLUP_PORT", "3306"));
        String user = env("ROLLUP_USER", "");
        String pass = env("ROLLUP_PASS", "");
        String dbsCsv = env("ROLLUP_DBS", "res_233_2,telcobright");
        String entityType = env("ROLLUP_ENTITY", "cdr");
        String reportPath = env("ROLLUP_DUMP_REPORT", "target/rollup-cdrdump.txt");

        var sb = new StringBuilder();
        try {
            for (String raw : dbsCsv.split(",")) {
                String db = raw.trim();
                if (db.isEmpty()) continue;
                line(sb, "");
                line(sb, "=== schema=" + db + " ===");
                try (Connection conn = open(host, port, db, user, pass);
                     Statement st = conn.createStatement();
                     ResultSet r = st.executeQuery("select id, op, data from summary_affected where entity_type='"
                             + entityType + "' order by id limit 3")) {
                    while (r.next()) {
                        long id = r.getLong("id");
                        String op = r.getString("op");
                        List<Entry> entries = SummaryOutboxWriter.Decode(r.getString("data"));
                        line(sb, "outbox id=" + id + " op=" + op + " entries=" + entries.size());
                        int ei = 0;
                        for (Entry e : entries) {
                            line(sb, "  --- entry[" + ei + "] CDR ---");
                            dumpObj(sb, e.Cdr());
                            var legs = e.Chargeables();
                            line(sb, "  --- chargeable legs: " + (legs == null ? 0 : legs.size()) + " ---");
                            if (legs != null) {
                                int li = 0;
                                for (acc_chargeable c : legs) {
                                    line(sb, "  leg[" + (li++) + "] assignedDirection=" + c.assignedDirection
                                            + " servicegroup=" + c.servicegroup);
                                    dumpObj(sb, c);
                                }
                            }
                            var cust = e.Customer();
                            line(sb, "  customer leg = " + (cust == null ? "null"
                                    : ("sg=" + cust.servicegroup + " dir=" + cust.assignedDirection)));
                            if (++ei >= 2) { line(sb, "  ...(more entries omitted)"); break; }
                        }
                    }
                } catch (Exception ex) {
                    line(sb, "  ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        } finally {
            String rep = sb.toString();
            System.out.println(rep);
            try { Files.writeString(Path.of(reportPath), rep); } catch (IOException ignore) { /* best effort */ }
        }
    }

    private static void dumpObj(StringBuilder sb, Object o) {
        if (o == null) { line(sb, "    (null)"); return; }
        var vals = new TreeMap<String, String>();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    vals.put(f.getName(), v == null ? "null" : String.valueOf(v));
                } catch (Exception ignore) { /* skip */ }
            }
        }
        var empty = new ArrayList<String>();
        for (var en : vals.entrySet()) {
            String v = en.getValue();
            if (v.equals("null") || v.isEmpty()) empty.add(en.getKey());
            else line(sb, "    " + en.getKey() + " = " + v);
        }
        if (!empty.isEmpty()) line(sb, "    [null/empty] " + String.join(", ", empty));
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
