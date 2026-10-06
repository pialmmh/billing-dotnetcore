package com.telcobright.billing.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.account;
import com.telcobright.billing.mediation.sms.ISmsAccountingStore;
import com.telcobright.billing.mediation.sms.SmsBillingRuleCatalog;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live {@link ISmsAccountingStore}: runs on the BATCH's own connection, inside its transaction, under its
 * per-schema lock. Account and ledger rows are read {@code FOR UPDATE}, so a balance is computed off a row no
 * other writer (e.g. a portal top-up) can change until the batch commits.
 */
public final class MySqlSmsAccountingStore implements ISmsAccountingStore {
    private static final Logger log = Logger.getLogger(MySqlSmsAccountingStore.class);
    private static final ObjectMapper Json = new ObjectMapper();
    private static final int Chunk = 500;

    private final Connection _conn;

    public MySqlSmsAccountingStore(Connection conn) {
        _conn = conn;
    }

    /**
     * {@code billingruleassignment} + {@code jsonbillingrule}. The rule's prepaid flag comes from its
     * {@code JsonExpression} (legacy deserialized exactly that — {@code JsonBillingRuleToBillingRuleConverter});
     * when the {@code isPrepaid} column is also set it must agree. A rule whose JSON is unreadable or contradicts
     * the column is left OUT of the catalog (logged), so an SMS that needs it fails into cdrerror instead of being
     * posted to a guessed account type.
     */
    @Override
    public SmsBillingRuleCatalog LoadBillingRuleCatalog() {
        var assignments = new HashMap<Integer, SmsBillingRuleCatalog.Assignment>();
        var rules = new HashMap<Integer, SmsBillingRuleCatalog.BillingRule>();
        try (PreparedStatement ps = _conn.prepareStatement(
                "select idRatePlanAssignmentTuple, idBillingRule, idServiceGroup from billingruleassignment");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                assignments.put(rs.getInt(1), new SmsBillingRuleCatalog.Assignment(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
        } catch (SQLException e) {
            throw new RuntimeException("loading billingruleassignment failed", e);
        }
        try (PreparedStatement ps = _conn.prepareStatement(
                "select id, ruleName, JsonExpression, isPrepaid from jsonbillingrule");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt(1);
                String name = rs.getString(2);
                String json = rs.getString(3);
                Object column = rs.getObject(4);
                Boolean columnPrepaid = column == null ? null : rs.getInt(4) != 0;
                var rule = ParseRule(id, name, json, columnPrepaid);
                if (rule != null) rules.put(id, rule);
            }
        } catch (SQLException e) {
            throw new RuntimeException("loading jsonbillingrule failed", e);
        }
        return new SmsBillingRuleCatalog(Map.copyOf(assignments), Map.copyOf(rules));
    }

    /** Package-visible for the unit test: JSON {@code IsPrepaid} is authoritative; the column must agree if set. */
    static SmsBillingRuleCatalog.BillingRule ParseRule(int id, String name, String jsonExpression, Boolean columnPrepaid) {
        Boolean jsonPrepaid = null;
        if (jsonExpression != null && !jsonExpression.isBlank()) {
            try {
                JsonNode node = Json.readTree(jsonExpression);
                JsonNode p = node.get("IsPrepaid");
                if (p != null && p.isBoolean()) jsonPrepaid = p.booleanValue();
            } catch (Exception ex) {
                log.errorf("jsonbillingrule %d: JsonExpression is not valid JSON (%s) — rule left out", id, ex.getMessage());
                return null;
            }
        }
        if (jsonPrepaid == null && columnPrepaid == null) {
            log.errorf("jsonbillingrule %d: neither JsonExpression.IsPrepaid nor isPrepaid is set — rule left out", id);
            return null;
        }
        if (jsonPrepaid != null && columnPrepaid != null && !jsonPrepaid.equals(columnPrepaid)) {
            log.errorf("jsonbillingrule %d: JsonExpression.IsPrepaid=%s contradicts isPrepaid=%s — rule left out",
                    id, jsonPrepaid, columnPrepaid);
            return null;
        }
        return new SmsBillingRuleCatalog.BillingRule(id, name, jsonPrepaid != null ? jsonPrepaid : columnPrepaid);
    }

    @Override
    public Map<String, account> LockAccountsByName(Collection<String> accountNames) {
        var found = new HashMap<String, account>();
        var names = new ArrayList<>(accountNames);
        for (int i = 0; i < names.size(); i += Chunk) {
            var slice = names.subList(i, Math.min(i + Chunk, names.size()));
            String sql = "select id,idParent,idParentExternal,idPartner,accountName,serviceGroup,serviceFamily,product,"
                    + "billableType,uom,Depth,Lineage,remark,isBillable,isCustomerAccount,isSupplierAccount,"
                    + "balanceBefore,lastAmount,balanceAfter,lastUpdated,superviseNegativeBalance,negativeBalanceLimit"
                    + " from account where accountName in (" + Placeholders(slice.size()) + ") for update";
            try (PreparedStatement ps = _conn.prepareStatement(sql)) {
                for (int j = 0; j < slice.size(); j++) ps.setString(j + 1, slice.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        account a = new account();
                        a.id = rs.getLong("id");
                        a.idParent = (Long) rs.getObject("idParent", Long.class);
                        a.idParentExternal = rs.getString("idParentExternal");
                        a.idPartner = rs.getInt("idPartner");
                        a.accountName = rs.getString("accountName");
                        a.serviceGroup = rs.getInt("serviceGroup");
                        a.serviceFamily = rs.getInt("serviceFamily");
                        a.product = rs.getInt("product");
                        a.billableType = rs.getString("billableType");
                        a.uom = rs.getString("uom");
                        a.Depth = rs.getInt("Depth");
                        a.Lineage = rs.getString("Lineage");
                        a.remark = rs.getString("remark");
                        a.isBillable = rs.getObject("isBillable", Integer.class);
                        a.isCustomerAccount = rs.getObject("isCustomerAccount", Integer.class);
                        a.isSupplierAccount = rs.getObject("isSupplierAccount", Integer.class);
                        a.balanceBefore = Nz(rs.getBigDecimal("balanceBefore"));
                        a.lastAmount = rs.getBigDecimal("lastAmount");
                        a.balanceAfter = Nz(rs.getBigDecimal("balanceAfter"));
                        Timestamp ts = rs.getTimestamp("lastUpdated");
                        a.lastUpdated = ts != null ? ts.toLocalDateTime() : null;
                        a.superviseNegativeBalance = rs.getObject("superviseNegativeBalance", Integer.class);
                        a.negativeBalanceLimit = Nz(rs.getBigDecimal("negativeBalanceLimit"));
                        found.put(a.accountName, a);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("locking accounts failed", e);
            }
        }
        return found;
    }

    @Override
    public List<acc_ledger_summary> LockLedgerRows(Collection<Long> accountIds, Collection<LocalDateTime> transactionDates) {
        var rows = new ArrayList<acc_ledger_summary>();
        if (accountIds.isEmpty() || transactionDates.isEmpty()) return rows;
        var dates = new ArrayList<>(transactionDates);
        var ids = new ArrayList<>(accountIds);
        // transactionDate first: it is the partitioning column, so the lock touches only the involved partitions.
        String sql = "select id,idAccount,transactionDate,AMOUNT from acc_ledger_summary where transactionDate in ("
                + Placeholders(dates.size()) + ") and idAccount in (" + Placeholders(ids.size()) + ") for update";
        try (PreparedStatement ps = _conn.prepareStatement(sql)) {
            int i = 1;
            for (LocalDateTime d : dates) ps.setTimestamp(i++, Timestamp.valueOf(d));
            for (Long id : ids) ps.setLong(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var r = new acc_ledger_summary();
                    r.id = rs.getLong(1);
                    r.idAccount = rs.getLong(2);
                    r.transactionDate = rs.getTimestamp(3).toLocalDateTime();
                    r.AMOUNT = Nz(rs.getBigDecimal(4));
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("locking acc_ledger_summary rows failed", e);
        }
        return rows;
    }

    private static String Placeholders(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    private static BigDecimal Nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
