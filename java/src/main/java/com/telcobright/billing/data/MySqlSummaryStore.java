package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_03;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_03;
import com.telcobright.billing.mediation.summary.ISummaryStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The live {@link ISummaryStore} over MySQL (JDBC). It runs on a caller-supplied connection so it shares the
 * single per-call connection the atomic write uses. {@code LoadByStartTimes} is the PopulatePrevSummary load
 * (SELECT the existing rows for the call's bucketed start times); {@code ExecuteNonQuery} is the cache's
 * write executor.
 *
 * <p>FAITHFUL-PORT NOTE (MySqlConnector -&gt; JDBC): the C# optional {@code MySqlTransaction? tx} param is
 * dropped — JDBC has no separate transaction object; statements created from the connection already run in
 * the connection's current transaction (the batch runner owns it via setAutoCommit(false)).</p>
 */
public final class MySqlSummaryStore implements ISummaryStore {
    private final Connection _conn;

    public MySqlSummaryStore(Connection conn) {
        _conn = conn;
    }

    @Override
    public int ExecuteNonQuery(String sql) {
        try (Statement st = _conn.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, Collection<LocalDateTime> startTimes) {
        if (startTimes.isEmpty()) return List.of();

        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        var inList = startTimes.stream().map(t -> "'" + t.format(fmt) + "'").collect(Collectors.joining(","));
        var sql = "select " + AbstractCdrSummary.ExtInsertColumns + " from " + table + " where tup_starttime in (" + inList + ")";

        try (Statement st = _conn.createStatement();
             ResultSet reader = st.executeQuery(sql)) {
            var rows = new ArrayList<AbstractCdrSummary>();
            while (reader.next())
                rows.add(MapRow(table, reader));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static AbstractCdrSummary MapRow(CdrSummaryType table, ResultSet r) throws SQLException {
        AbstractCdrSummary s = switch (table) {
            case sum_voice_day_02 -> new sum_voice_day_02();
            case sum_voice_day_03 -> new sum_voice_day_03();
            case sum_voice_hr_02 -> new sum_voice_hr_02();
            case sum_voice_hr_03 -> new sum_voice_hr_03();
            default -> throw new UnsupportedOperationException("No summary entity mapped for " + table + ".");
        };

        s.id = L(r, "id");
        s.tup_switchid = I(r, "tup_switchid");
        s.tup_inpartnerid = I(r, "tup_inpartnerid");
        s.tup_outpartnerid = I(r, "tup_outpartnerid");
        s.tup_incomingroute = S(r, "tup_incomingroute");
        s.tup_outgoingroute = S(r, "tup_outgoingroute");
        s.tup_customerrate = D(r, "tup_customerrate");
        s.tup_supplierrate = D(r, "tup_supplierrate");
        s.tup_incomingip = S(r, "tup_incomingip");
        s.tup_outgoingip = S(r, "tup_outgoingip");
        s.tup_countryorareacode = S(r, "tup_countryorareacode");
        s.tup_matchedprefixcustomer = S(r, "tup_matchedprefixcustomer");
        s.tup_matchedprefixsupplier = S(r, "tup_matchedprefixsupplier");
        s.tup_sourceId = S(r, "tup_sourceId");
        s.tup_destinationId = S(r, "tup_destinationId");
        s.tup_customercurrency = S(r, "tup_customercurrency");
        s.tup_suppliercurrency = S(r, "tup_suppliercurrency");
        s.tup_tax1currency = S(r, "tup_tax1currency");
        s.tup_tax2currency = S(r, "tup_tax2currency");
        s.tup_vatcurrency = S(r, "tup_vatcurrency");
        s.tup_starttime = Dt(r, "tup_starttime");
        s.totalcalls = L(r, "totalcalls");
        s.connectedcalls = L(r, "connectedcalls");
        s.connectedcallsCC = L(r, "connectedcallsCC");
        s.successfulcalls = L(r, "successfulcalls");
        s.actualduration = D(r, "actualduration");
        s.roundedduration = D(r, "roundedduration");
        s.duration1 = D(r, "duration1");
        s.duration2 = D(r, "duration2");
        s.duration3 = D(r, "duration3");
        s.PDD = D(r, "PDD");
        s.customercost = D(r, "customercost");
        s.suppliercost = D(r, "suppliercost");
        s.tax1 = D(r, "tax1");
        s.tax2 = D(r, "tax2");
        s.vat = D(r, "vat");
        s.intAmount1 = I(r, "intAmount1");
        s.intAmount2 = I(r, "intAmount2");
        s.longAmount1 = L(r, "longAmount1");
        s.longAmount2 = L(r, "longAmount2");
        s.longDecimalAmount1 = D(r, "longDecimalAmount1");
        s.longDecimalAmount2 = D(r, "longDecimalAmount2");
        s.intAmount3 = I(r, "intAmount3");
        s.longAmount3 = L(r, "longAmount3");
        s.longDecimalAmount3 = D(r, "longDecimalAmount3");
        s.decimalAmount1 = D(r, "decimalAmount1");
        s.decimalAmount2 = D(r, "decimalAmount2");
        s.decimalAmount3 = D(r, "decimalAmount3");
        return s;
    }

    private static long L(ResultSet r, String c) throws SQLException { return r.getLong(c); }
    private static int I(ResultSet r, String c) throws SQLException { return r.getInt(c); }
    private static java.math.BigDecimal D(ResultSet r, String c) throws SQLException { return r.getBigDecimal(c); }
    private static LocalDateTime Dt(ResultSet r, String c) throws SQLException { return r.getTimestamp(c).toLocalDateTime(); }
    private static String S(ResultSet r, String c) throws SQLException { var v = r.getString(c); return v == null ? "" : v; }
}
