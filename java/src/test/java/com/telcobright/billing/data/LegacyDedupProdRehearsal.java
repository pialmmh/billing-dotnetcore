// READ-ONLY production rehearsal of the EXACT flag-ON lookup path (MySqlCdrBatchRunner.FilterLegacyOwned +
// jdbcOwnedSeqs) against real .110 legacy data. NO writes. Guarded by system properties so it is SKIPPED in
// normal CI; invoke explicitly, e.g.:
//   mvn -Dtest=LegacyDedupProdRehearsal test \
//     -Drehearsal.url='jdbc:mysql://HOST:3306/telcobright' -Drehearsal.user=... -Drehearsal.pw=...
package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.cdr;

/** Proves cdr-owned→SKIP, cdrerror-owned→SKIP, neither→WOULD_PROCESS on live .110, and schema isolation. */
class LegacyDedupProdRehearsal {

    private static String p(String k) { return System.getProperty(k); }

    private static cdr seq(long s) { cdr c = new cdr(); c.SequenceNumber = s; c.UniqueBillId = "u-" + s; return c; }

    private static List<Long> pickSeqs(Connection conn, String table, int n) throws SQLException {
        var out = new ArrayList<Long>();
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT SequenceNumber FROM " + table
                     + " WHERE SequenceNumber>0 ORDER BY SequenceNumber DESC LIMIT " + n)) {
            while (rs.next()) out.add(rs.getLong(1));
        }
        return out;
    }

    @Test
    void ReadOnly_prod_rehearsal_against_110() throws Exception {
        String url = p("rehearsal.url");
        assumeTrue(url != null && !url.isBlank(), "rehearsal.url not set — skipping prod rehearsal");

        try (Connection conn = DriverManager.getConnection(url, p("rehearsal.user"), p("rehearsal.pw"))) {
            conn.setReadOnly(true);                 // belt: advise the driver this session is read-only
            conn.setAutoCommit(true);
            String schema = conn.getCatalog();
            System.out.println("\n================ LEGACY-DEDUP READ-ONLY REHEARSAL — schema=" + schema + " ================");

            List<Long> cdrSeqs = pickSeqs(conn, "cdr", 3);
            List<Long> errSeqs = pickSeqs(conn, "cdrerror", 3);
            List<Long> fakeSeqs = List.of(999_999_001L, 999_999_002L, 999_999_003L);
            boolean populated = !cdrSeqs.isEmpty();
            if (!populated)
                System.out.println("(schema " + schema + " has NO legacy billing data → every call WOULD_PROCESS; "
                        + "running schema-isolation + fail-closed checks only)");

            // representative MIXED batch: cdr-owned + cdrerror-owned + neither + a DUPLICATE of a cdr seq, interleaved
            var sample = new ArrayList<cdr>();
            for (int i = 0; i < Math.max(cdrSeqs.size(), fakeSeqs.size()); i++) {
                if (i < cdrSeqs.size()) sample.add(seq(cdrSeqs.get(i)));
                if (i < errSeqs.size()) sample.add(seq(errSeqs.get(i)));
                if (i < fakeSeqs.size()) sample.add(seq(fakeSeqs.get(i)));
            }
            if (!cdrSeqs.isEmpty()) sample.add(seq(cdrSeqs.get(0)));   // duplicate of an owned seq

            // === invoke the EXACT production path: FilterLegacyOwned + the real jdbcOwnedSeqs (cdr UNION cdrerror) ===
            long t0 = System.nanoTime();
            List<cdr> kept = MySqlCdrBatchRunner.FilterLegacyOwned(sample,
                    cands -> MySqlCdrBatchRunner.jdbcOwnedSeqs(conn, cands));
            long micros = (System.nanoTime() - t0) / 1000;

            Set<Long> keptSeqs = new TreeSet<>();
            for (cdr c : kept) keptSeqs.add(c.SequenceNumber);
            Set<Long> owned = new TreeSet<>();                        // what the lookup itself found (for the table)
            owned.addAll(MySqlCdrBatchRunner.jdbcOwnedSeqs(conn, new LinkedHashSet<>(
                    concat(cdrSeqs, errSeqs, fakeSeqs))));

            // evidence table
            System.out.printf("%-14s %-16s %-14s%n", "SequenceNumber", "source", "decision");
            printRows(cdrSeqs,  "prod cdr",      keptSeqs);
            printRows(errSeqs,  "prod cdrerror", keptSeqs);
            printRows(fakeSeqs, "neither",       keptSeqs);
            System.out.println("lookup latency: " + micros + " µs for " + sample.size()
                    + " records (" + owned.size() + " owned)");

            // === assertions: cdr/cdrerror → SKIP (not kept); neither → WOULD_PROCESS (kept) ===
            for (Long s : cdrSeqs) assertFalse(keptSeqs.contains(s), "cdr-owned seq " + s + " must be SKIPPED");
            for (Long s : errSeqs) assertFalse(keptSeqs.contains(s), "cdrerror-owned seq " + s + " must be SKIPPED");
            for (Long s : fakeSeqs) assertTrue(keptSeqs.contains(s), "unowned seq " + s + " must be WOULD_PROCESS");
            if (!populated)
                assertEquals(sample.size(), keptSeqs.size(), "empty schema → nothing owned → all WOULD_PROCESS");

            // schema-routing cross-check: a seq known to live ONLY in another tenant (passed in) must NOT be found here
            String foreign = p("rehearsal.foreignSeq");
            if (foreign != null && !foreign.isBlank()) {
                long fs = Long.parseLong(foreign.trim());
                Set<Long> found = MySqlCdrBatchRunner.jdbcOwnedSeqs(conn, Set.of(fs));
                assertTrue(found.isEmpty(),
                        "foreign-tenant seq " + fs + " must NOT be found in schema " + schema + " (no cross-schema leak)");
                System.out.println("schema-routing: foreign seq " + fs + " NOT found in " + schema + " ✓");
            }

            // === fail-closed: a lookup SQLException must PROPAGATE (no ownership decision produced) ===
            SQLException boom = assertThrows(SQLException.class, () ->
                    MySqlCdrBatchRunner.FilterLegacyOwned(sample, cands -> { throw new SQLException("simulated db failure"); }));
            assertEquals("simulated db failure", boom.getMessage());
            System.out.println("fail-closed: lookup exception propagated (batch would roll back, no bill) ✓");
            System.out.println("read-only: conn.isReadOnly()=" + conn.isReadOnly() + "; only SELECTs issued ✓");
        }
    }

    private static List<Long> concat(List<Long>... ls) { var o = new ArrayList<Long>(); for (var l : ls) o.addAll(l); return o; }

    private static void printRows(List<Long> seqs, String src, Set<Long> keptSeqs) {
        for (Long s : seqs)
            System.out.printf("%-14d %-16s %-14s%n", s, src, keptSeqs.contains(s) ? "WOULD_PROCESS" : "SKIP");
    }
}
