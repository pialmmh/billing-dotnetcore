package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.CdrRowSql;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * billing-core's own tables in a tenant schema on PostgreSQL (brief B7; ad-is-a-call §3: prime-context makes the
 * schema and grants the right to create in it — it does not make these tables). The first time this process serves a
 * schema, and then once a day, {@link #Ensure} — called under the tenant's batch lock, before the batch's work:
 *
 * <ol>
 *   <li><b>creates what is absent</b> of {@code cdr}, {@code cdrerror}, {@code acc_chargeable},
 *       {@code summary_affected} — each in ONE step with its indexes, its rights and, for a partitioned table, ALL
 *       its partitions: the months from {@code MonthsBack} before this one to {@code MonthsAhead} after it, and a
 *       DEFAULT partition, so that no time, however wrong, can refuse a row;</li>
 *   <li><b>adds the months ahead</b> that are not there yet. This step NEVER fails a batch: a month that cannot be
 *       added is an ERROR line and is tried again the next day; its rows go to the DEFAULT partition meanwhile.</li>
 * </ol>
 *
 * <p><b>The DEFAULT partition's trap.</b> PostgreSQL refuses to create a partition while the DEFAULT partition holds a
 * row of its range. So a month is added by MOVING: a plain table is made, the DEFAULT partition's rows of that month
 * are moved into it, and it is attached as the month's partition — one transaction, under a short lock timeout.
 *
 * <p><b>The rights</b> (architect's ruling 2026-10-04) are stated here, on every table and every partition this
 * class makes, whatever the schema's default privileges give: the summary service reads {@code cdr},
 * {@code cdrerror} and {@code acc_chargeable} and may delete from {@code summary_affected} only; it has no right at
 * all on a partition (a partition is a table, and a DELETE on one would erase the record of a call); the reader
 * roles read the four. A named role that does not exist stops the schema's first batch, by name.
 *
 * <p>Old months are never dropped: retention is the owner's rule.
 */
public final class PostgresTenantTables {
    private static final Logger log = Logger.getLogger(PostgresTenantTables.class);

    static final String DdlResource = "sql/postgres/billing-tables.sql";

    /** In creation order: {@code cdrerror} is {@code LIKE cdr}. */
    static final List<String> Tables = List.of("cdr", "cdrerror", "acc_chargeable", "summary_affected");

    /** The partitioned tables and the column each is partitioned on. */
    static final Map<String, String> PartitionKeys = PartitionKeysInOrder();

    private static final DateTimeFormatter PartitionSuffix = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern RoleName = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String MonthLockWait = "5s";

    /** What a deployment may set ({@code billing.datasource.postgres.*}). An empty role name = that grant is not made. */
    public record Options(String SummaryServiceRole, List<String> ReaderRoles, int MonthsBack, int MonthsAhead) {
        public Options {
            SummaryServiceRole = SummaryServiceRole == null ? "" : SummaryServiceRole.trim();
            ReaderRoles = ReaderRoles == null ? List.of() : List.copyOf(ReaderRoles);
            for (String role : AllRoles(SummaryServiceRole, ReaderRoles))
                if (!RoleName.matcher(role).matches())
                    throw new IllegalStateException("'" + role + "' is not a PostgreSQL role name (billing.datasource.postgres.grants)");
            if (MonthsBack < 0 || MonthsAhead < 0)
                throw new IllegalStateException("billing.datasource.postgres.partitions: months-back and months-ahead cannot be negative");
        }

        public static Options Defaults() {
            return new Options("summary_service", List.of("ad_sphere"), 1, 3);
        }

        private static List<String> AllRoles(String summary, List<String> readers) {
            List<String> all = new ArrayList<>(readers);
            if (!summary.isEmpty()) all.add(summary);
            return all;
        }
    }

    private final Options _opts;
    private final Supplier<LocalDate> _today;
    private final Map<String, List<String>> _ddl;                       // "table cdr" / "indexes cdr" → its statements
    private final Map<String, LocalDate> _readyOn = new ConcurrentHashMap<>();   // schema → the day it was last made ready

    public PostgresTenantTables(Options opts) {
        this(opts, LocalDate::now);
    }

    PostgresTenantTables(Options opts, Supplier<LocalDate> today) {
        _opts = opts;
        _today = today;
        _ddl = ReadTheDdl();
    }

    /**
     * Under the tenant's batch lock: the schema can take a batch after this. Commits what it makes. Answers the
     * month partitions that could NOT be added this time (normally none) — each is already an ERROR line; a
     * failure to CREATE a table is not in this list: it throws.
     */
    public List<String> Ensure(Connection conn, String schema) {
        LocalDate today = _today.get();
        if (today.equals(_readyOn.get(schema))) return List.of();

        CreateWhatIsAbsent(conn, schema, YearMonth.from(today));
        List<String> notAdded = AddTheMonthsAhead(conn, schema, YearMonth.from(today));
        _readyOn.put(schema, today);
        return notAdded;
    }

    /** A batch of this schema failed: look at its tables again before the next one. */
    public void Forget(String schema) {
        _readyOn.remove(schema);
    }

    // ── 1 · create what is absent ────────────────────────────────────────────────────────────────────────────

    private void CreateWhatIsAbsent(Connection conn, String schema, YearMonth thisMonth) {
        try {
            RefuseATableThatIsNotOurs(conn, schema);
            List<String> absent = AbsentTables(conn);
            if (!absent.isEmpty()) {
                Run(conn, StatementsToCreate(absent, thisMonth));
                log.infof("schema %s: billing-core's tables made — %s (the month partitions %s … %s and a DEFAULT one)",
                        schema, absent, Suffix(thisMonth.minusMonths(_opts.MonthsBack())), Suffix(thisMonth.plusMonths(_opts.MonthsAhead())));
            }
            conn.commit();
        } catch (SQLException e) {
            Rollback(conn);
            throw new IllegalStateException("billing-core's tables could not be made in schema " + schema + ": " + e.getMessage(), e);
        }
    }

    /** Every statement of the absent tables, in order: the table, all its partitions, its indexes, its rights. */
    List<String> StatementsToCreate(List<String> absent, YearMonth thisMonth) {
        List<String> statements = new ArrayList<>();
        for (String table : absent) {
            Map<String, String> partitions = FirstPartitionsOf(table, thisMonth);
            statements.addAll(_ddl.get("table " + table));
            statements.addAll(partitions.values());
            statements.addAll(_ddl.get("indexes " + table));
            statements.addAll(RightsOn(table, partitions.keySet()));
        }
        return statements;
    }

    /** The partitions a new table is made with — one per month of the window, then the DEFAULT one — each name
     * with the statement that makes it. Empty for a table that is not partitioned. */
    private Map<String, String> FirstPartitionsOf(String table, YearMonth thisMonth) {
        Map<String, String> partitions = new LinkedHashMap<>();
        if (!PartitionKeys.containsKey(table)) return partitions;
        for (int m = -_opts.MonthsBack(); m <= _opts.MonthsAhead(); m++) {
            YearMonth month = thisMonth.plusMonths(m);
            String name = PartitionName(table, month);
            partitions.put(name, "CREATE TABLE " + name + " PARTITION OF " + table + " " + BoundsOf(month));
        }
        String rest = DefaultPartitionName(table);
        partitions.put(rest, "CREATE TABLE " + rest + " PARTITION OF " + table + " DEFAULT");
        return partitions;
    }

    /** The rights of a table this class made, and of its partitions (see the class note). */
    List<String> RightsOn(String table, java.util.Collection<String> partitions) {
        List<String> rights = new ArrayList<>();
        String summary = _opts.SummaryServiceRole();
        if (!summary.isEmpty()) {
            rights.add("REVOKE ALL ON " + table + " FROM " + summary);
            rights.add("GRANT " + (table.equals("summary_affected") ? "SELECT, DELETE" : "SELECT") + " ON " + table + " TO " + summary);
            for (String partition : partitions) rights.add("REVOKE ALL ON " + partition + " FROM " + summary);
        }
        for (String reader : _opts.ReaderRoles()) rights.add("GRANT SELECT ON " + table + " TO " + reader);
        return rights;
    }

    private static List<String> AbsentTables(Connection conn) throws SQLException {
        List<String> absent = new ArrayList<>();
        for (String table : Tables) if (!Exists(conn, table)) absent.add(table);
        return absent;
    }

    /** A table of one of our names that lacks our columns is somebody else's (ad-sphere's PC stand-in makes a
     * {@code cdr} of its own shape). Writing into it would fail at every batch with a column error; say what it is. */
    private void RefuseATableThatIsNotOurs(Connection conn, String schema) throws SQLException {
        for (Map.Entry<String, Set<String>> expected : ExpectedColumns().entrySet()) {
            String table = expected.getKey();
            if (!Exists(conn, table)) continue;
            Set<String> missing = new HashSet<>(expected.getValue());
            missing.removeAll(ColumnsOf(conn, table));
            if (!missing.isEmpty())
                throw new IllegalStateException("schema " + schema + " already has a table '" + table + "' that is not billing-core's:"
                        + " it lacks the column(s) " + new java.util.TreeSet<>(missing) + ". billing-core makes its own tables"
                        + " (sql/postgres/billing-tables.sql) and never alters one it did not make — remove that table.");
        }
    }

    static Map<String, Set<String>> ExpectedColumns() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("cdr", Lower(CdrRowSql.PostgresColumns));
        expected.put("cdrerror", Lower(CdrRowSql.PostgresColumns));
        expected.put("acc_chargeable", Lower(acc_chargeable.ExtInsertColumns));
        expected.put("summary_affected", Lower("id,entity_type,op,data"));
        return expected;
    }

    private static Set<String> Lower(String columns) {
        Set<String> names = new HashSet<>();
        for (String c : columns.split(",")) names.add(c.trim().toLowerCase(Locale.ROOT));
        return names;
    }

    // ── 2 · add the months ahead (never fails a batch) ───────────────────────────────────────────────────────

    /** The names of the months that could not be added. */
    private List<String> AddTheMonthsAhead(Connection conn, String schema, YearMonth thisMonth) {
        List<String> notAdded = new ArrayList<>();
        for (String table : PartitionKeys.keySet())
            for (int m = 0; m <= _opts.MonthsAhead(); m++)
                if (!AddTheMonthIfMissing(conn, schema, table, thisMonth.plusMonths(m)))
                    notAdded.add(PartitionName(table, thisMonth.plusMonths(m)));
        return notAdded;
    }

    /** {@code false} = the month is not there and could not be added (one ERROR line; never an exception). */
    private boolean AddTheMonthIfMissing(Connection conn, String schema, String table, YearMonth month) {
        String partition = PartitionName(table, month);
        try {
            if (IsPartitionOf(conn, table, partition)) { conn.commit(); return true; }
            Run(conn, StatementsToAddAMonth(table, month));
            conn.commit();
            log.infof("schema %s: partition %s added", schema, partition);
            return true;
        } catch (SQLException e) {
            Rollback(conn);
            log.errorf("schema %s: partition %s could NOT be added (%s) — its rows go to %s meanwhile; tried again tomorrow",
                    schema, partition, e.getMessage(), DefaultPartitionName(table));
            return false;
        }
    }

    /** One month, by moving (see the class note): a plain table, the DEFAULT partition's rows of that month moved
     * into it, then attached as the partition. The lock timeout is the transaction's only. */
    List<String> StatementsToAddAMonth(String table, YearMonth month) {
        String partition = PartitionName(table, month);
        String key = PartitionKeys.get(table);
        String from = Stamp(month), to = Stamp(month.plusMonths(1));
        List<String> statements = new ArrayList<>();
        statements.add("SET LOCAL lock_timeout = '" + MonthLockWait + "'");
        statements.add("CREATE TABLE " + partition + " (LIKE " + table + " INCLUDING DEFAULTS)");
        statements.add("WITH moved AS (DELETE FROM " + DefaultPartitionName(table) + " WHERE " + key + " >= '" + from + "' AND "
                + key + " < '" + to + "' RETURNING *) INSERT INTO " + partition + " SELECT * FROM moved");
        statements.add("ALTER TABLE " + table + " ATTACH PARTITION " + partition + " " + BoundsOf(month));
        if (!_opts.SummaryServiceRole().isEmpty())
            statements.add("REVOKE ALL ON " + partition + " FROM " + _opts.SummaryServiceRole());
        return statements;
    }

    // ── names, bounds, small SQL ─────────────────────────────────────────────────────────────────────────────

    static String PartitionName(String table, YearMonth month) {
        return table + "_p" + Suffix(month);
    }

    static String DefaultPartitionName(String table) {
        return table + "_pdefault";
    }

    private static String Suffix(YearMonth month) {
        return month.format(PartitionSuffix);
    }

    private static String BoundsOf(YearMonth month) {
        return "FOR VALUES FROM ('" + Stamp(month) + "') TO ('" + Stamp(month.plusMonths(1)) + "')";
    }

    private static String Stamp(YearMonth month) {
        return month.atDay(1) + " 00:00:00";
    }

    /** Is there such a table in the tenant's schema (the connection's search path)? */
    private static boolean Exists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("select to_regclass(?) is not null")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Is the named relation a partition OF the table? (A table of that name that is not one does not count: it is
     * in the way, and adding the month then fails and says so.) */
    private static boolean IsPartitionOf(Connection conn, String table, String partition) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "select exists (select 1 from pg_inherits where inhparent = to_regclass(?) and inhrelid = to_regclass(?))")) {
            ps.setString(1, table);
            ps.setString(2, partition);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static Set<String> ColumnsOf(Connection conn, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select column_name from information_schema.columns where table_schema = current_schema() and table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static void Run(Connection conn, List<String> statements) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    private static void Rollback(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) { /* the caller's catch reports the first failure */ }
    }

    private static Map<String, String> PartitionKeysInOrder() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("cdr", "StartTime");
        keys.put("cdrerror", "StartTime");
        keys.put("acc_chargeable", "transactionTime");
        return keys;
    }

    // ── the DDL file ─────────────────────────────────────────────────────────────────────────────────────────

    /** The file's sections ({@code -- @table <name>}, {@code -- @indexes <name>}) → their statements, comments removed. */
    static Map<String, List<String>> ReadTheDdl() {
        String text;
        try (InputStream in = PostgresTenantTables.class.getClassLoader().getResourceAsStream(DdlResource)) {
            if (in == null) throw new IllegalStateException("the DDL resource " + DdlResource + " is not on the classpath");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, List<String>> sections = new HashMap<>();
        Matcher marker = Pattern.compile("(?m)^-- @(table|indexes) (\\w+)\\s*$").matcher(text);
        int bodyStart = -1;
        String name = null;
        while (marker.find()) {
            if (name != null) sections.put(name, StatementsOf(text.substring(bodyStart, marker.start())));
            name = marker.group(1) + " " + marker.group(2);
            bodyStart = marker.end();
        }
        if (name != null) sections.put(name, StatementsOf(text.substring(bodyStart)));
        for (String table : Tables)
            if (!sections.containsKey("table " + table) || !sections.containsKey("indexes " + table))
                throw new IllegalStateException(DdlResource + " has no '@table " + table + "' / '@indexes " + table + "' section");
        return sections;
    }

    private static List<String> StatementsOf(String body) {
        StringBuilder sql = new StringBuilder();
        for (String line : body.split("\n"))
            if (!line.stripLeading().startsWith("--")) sql.append(line).append('\n');
        List<String> statements = new ArrayList<>();
        for (String statement : sql.toString().split(";"))
            if (!statement.isBlank()) statements.add(statement.strip());
        return statements;
    }
}
