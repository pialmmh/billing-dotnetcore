package com.telcobright.billing.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.mediation.cdr.CdrBatch;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.account;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sms.SmsBillingRuleCatalog;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.mediation.summary.CdrSummaryContext;
import com.telcobright.billing.testsupport.SmsTestData;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * READ-ONLY legacy parity rehearsal for outgoing SMS: replays every SMS the legacy biller billed (from a dump of the
 * tenant schema — never committed, the path is passed in) through {@code CdrPipeline.SmsOutgoing} over an in-memory
 * DB, in legacy transaction order, and compares rate selection, amounts, quantities, cdr totals, the transaction
 * balance chain, final account balances, the ledger and the _01 summaries.
 *
 * <p>Each SMS is bound to the rate plan legacy actually rated it with ({@code acc_chargeable.RateId → rate.idrateplan})
 * because the schema keeps only the CURRENT plan assignment per tuple. Skipped unless
 * {@code -Dsms.parity.dump=<dump.json>} is given.</p>
 */
class SmsLegacyParityRehearsal {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final List<String> mismatches = new ArrayList<>();

    @Test
    void replay_legacy_sms_through_the_new_pipeline() throws Exception {
        String path = System.getProperty("sms.parity.dump");
        Assumptions.assumeTrue(path != null && new File(path).isFile(), "no -Dsms.parity.dump given");
        Map<String, List<Map<String, String>>> d = new ObjectMapper().readValue(new File(path), new TypeReference<>() {});

        var cdrByBill = Index(d.get("cdr"), "UniqueBillId");
        var chgByBill = Index(d.get("chg"), "uniqueBillId");
        var rateById = Index(d.get("rate"), "id");
        var planById = Index(d.get("plan"), "id");
        var legacyAcctById = Index(d.get("acct"), "id");
        // Replay in legacy's BALANCE-APPLICATION order, not transaction-id order: legacy mediated a file's SMS in
        // parallel, so its ids and its balance chain disagree on order. Every SMS charge is negative, so per account
        // the chain is strictly decreasing in BalanceBefore — that IS the order legacy applied them in.
        var txns = new ArrayList<>(d.get("txn"));
        txns.sort(Comparator.<Map<String, String>>comparingLong(t -> Long.parseLong(t.get("glAccountId")))
                .thenComparing(t -> new BigDecimal(t.get("BalanceBefore")), Comparator.reverseOrder())
                .thenComparingLong(t -> Long.parseLong(t.get("id"))));

        var store = new SmsTestData.InMemoryAccounting(null);
        var ids = new CountingAutoIncrementManager(1);
        var ourSummaries = new TreeMap<String, BigDecimal[]>();
        int compared = 0;

        for (var tx : txns) {
            String bill = tx.get("uniqueBillId");
            var lc = cdrByBill.get(bill);
            var lch = chgByBill.get(bill);
            int plan = Integer.parseInt(rateById.get(lch.get("RateId")).get("idrateplan"));
            int partner = Integer.parseInt(lc.get("InPartnerId"));
            int rule = Integer.parseInt(lch.get("idBillingrule"));

            var t = Tenant(d, plan, partner, rule, planById);
            store = Rebind(store, t.Catalog());
            cdr c = SmsTestData.Sms(lc.get("OriginatingCallingNumber"), lc.get("OriginatingCalledNumber"), partner,
                    new BigDecimal(lc.get("DurationSec")).intValue(), lc.get("SequenceNumber"), Ts(lc.get("StartTime")));
            c.OutPartnerId = Integer.parseInt(lc.get("OutPartnerId"));
            c.IdCall = Long.parseLong(lc.get("IdCall"));
            var sql = new SmsTestData.CapturingSql();
            CdrBatchResult r = CdrPipeline.SmsOutgoing(t.Catalog(), store, () -> Ts(tx.get("transactionTime")))
                    .Process(new CdrBatch(t.Mediation(), t.Partners, List.of(c), sql, ids));
            Apply(store, sql);
            String key = "sms " + bill + " (" + lc.get("StartTime") + ")";
            if (r.Rated().size() != 1) { mismatches.add(key + ": NOT rated: " + r.Errored().get(0).ErrorCode); continue; }
            compared++;

            var nc = r.Rated().get(0).Cdr();
            var ch = r.Rated().get(0).Chargeables().get(0);
            Eq(key, "RateId", lch.get("RateId"), Long.toString(ch.RateId));
            Eq(key, "Prefix", lch.get("Prefix"), ch.Prefix);
            Num(key, "unitPriceOrCharge", lch.get("unitPriceOrCharge"), ch.unitPriceOrCharge);
            Num(key, "BilledAmount", lch.get("BilledAmount"), ch.BilledAmount);
            Num(key, "Quantity", lch.get("Quantity"), ch.Quantity);
            Eq(key, "ProductId", lch.get("ProductId"), Long.toString(ch.ProductId));
            Eq(key, "idBilledUom", lch.get("idBilledUom"), ch.idBilledUom);
            Eq(key, "idQuantityUom", lch.get("idQuantityUom"), ch.idQuantityUom);
            Eq(key, "servicegroup", lch.get("servicegroup"), Integer.toString(ch.servicegroup));
            Eq(key, "servicefamily", lch.get("servicefamily"), Integer.toString(ch.servicefamily));
            Eq(key, "assignedDirection", lch.get("assignedDirection"), ch.assignedDirection.toString());
            Eq(key, "description", lch.get("description"), ch.description);
            Eq(key, "idBillingrule", lch.get("idBillingrule"), Integer.toString(ch.idBillingrule));
            Num(key, "TaxAmount1", lch.get("TaxAmount1"), ch.TaxAmount1);
            Eq(key, "idEvent", lch.get("idEvent"), Long.toString(ch.idEvent));
            Eq(key, "glAccount(name)", legacyAcctById.get(lch.get("glAccountId")).get("accountName"), NameOf(store, ch.glAccountId));

            Eq(key, "cdr.MatchedPrefixCustomer", lc.get("MatchedPrefixCustomer"), nc.MatchedPrefixCustomer);
            Num(key, "cdr.CustomerRate", lc.get("CustomerRate"), nc.CustomerRate);
            Num(key, "cdr.InPartnerCost", lc.get("InPartnerCost"), nc.InPartnerCost);
            Eq(key, "cdr.CountryCode", lc.get("CountryCode"), nc.CountryCode);
            Num(key, "cdr.Duration1", lc.get("Duration1"), nc.Duration1);
            Eq(key, "cdr.RoundedDuration", lc.get("RoundedDuration"), nc.RoundedDuration == null ? null : nc.RoundedDuration.toPlainString());
            Num(key, "cdr.ChargeableMetaTotal", lc.get("ChargeableMetaTotal"), nc.ChargeableMetaTotal);
            Num(key, "cdr.TransactionMetaTotal", lc.get("TransactionMetaTotal"), nc.TransactionMetaTotal);
            Num(key, "cdr.SummaryMetaTotal", lc.get("SummaryMetaTotal"), nc.SummaryMetaTotal);
            Num(key, "cdr.Tax1", lc.get("Tax1"), nc.Tax1);
            Eq(key, "cdr.ServiceGroup", lc.get("ServiceGroup"), Integer.toString(nc.ServiceGroup));

            var nt = SmsTestData.InsertRow(sql.StartingWith("insert into acc_transaction (").get(0));
            Num(key, "txn.amount", tx.get("amount"), new BigDecimal(nt.get("amount")));
            Num(key, "txn.BalanceBefore", tx.get("BalanceBefore"), new BigDecimal(nt.get("BalanceBefore")));
            Num(key, "txn.BalanceAfter", tx.get("BalanceAfter"), new BigDecimal(nt.get("BalanceAfter")));
            Eq(key, "txn.debitOrCredit", tx.get("debitOrCredit"), Unq(nt.get("debitOrCredit")));
            Eq(key, "txn.uomId", tx.get("uomId"), Unq(nt.get("uomId")));
            Eq(key, "txn.description", tx.get("description"), Unq(nt.get("description")));
            Eq(key, "txn.isBillable", tx.get("isBillable"), Unq(nt.get("isBillable")));
            Eq(key, "txn.isPrepaid", tx.get("isPrepaid"), Unq(nt.get("isPrepaid")));
            Eq(key, "txn.isBilled", tx.get("isBilled"), Unq(nt.get("isBilled")));
            Eq(key, "txn.transactionTime", tx.get("transactionTime"), Unq(nt.get("transactionTime")));
            Eq(key, "txn.idEvent", tx.get("idEvent"), nt.get("idEvent"));

            var rated = r.Rated().get(0);
            for (var e : new CdrSummaryContext(null, ids).GenerateSummary(rated.Cdr(), rated.Customer()).entrySet())
                Fold(ourSummaries, e.getKey(), e.getValue());
        }

        // final account balances (every account the replay touched)
        for (var la : d.get("acct")) {
            account ours = store.Accounts.get(la.get("accountName"));
            if (ours == null) continue;
            Num("account " + la.get("accountName"), "balanceAfter", la.get("balanceAfter"), ours.balanceAfter);
            Num("account " + la.get("accountName"), "balanceBefore", la.get("balanceBefore"), ours.balanceBefore);
            Num("account " + la.get("accountName"), "lastAmount", la.get("lastAmount"), ours.lastAmount);
        }
        // ledger
        for (var ll : d.get("ledger")) {
            String name = legacyAcctById.get(ll.get("idAccount")).get("accountName");
            account ours = store.Accounts.get(name);
            if (ours == null) { mismatches.add("ledger " + name + " " + ll.get("transactionDate") + ": account never posted"); continue; }
            LocalDateTime day = Ts(ll.get("transactionDate"));
            var row = store.Ledger.stream().filter(x -> x.idAccount == ours.id && x.transactionDate.equals(day)).toList();
            if (row.size() != 1) { mismatches.add("ledger " + name + " " + day + ": rows=" + row.size()); continue; }
            Num("ledger " + name + " " + day, "AMOUNT", ll.get("AMOUNT"), row.get(0).AMOUNT);
        }
        // _01 summaries, grouped by (table, bucket, in, out, prefix, rate)
        var legacySummaries = new TreeMap<String, BigDecimal[]>();
        for (String table : List.of("sumday", "sumhr"))
            for (var s : d.get(table)) {
                String k = (table.equals("sumday") ? "sum_voice_day_01" : "sum_voice_hr_01") + "|" + s.get("tup_starttime")
                        + "|" + s.get("tup_inpartnerid") + "|" + s.get("tup_outpartnerid") + "|" + s.get("tup_matchedprefixcustomer")
                        + "|" + new BigDecimal(s.get("tup_customerrate")).stripTrailingZeros().toPlainString();
                Add(legacySummaries, k, s.get("totalcalls"), s.get("successfulcalls"), s.get("actualduration"),
                        s.get("duration1"), s.get("customercost"), s.get("roundedduration"));
            }
        for (var k : legacySummaries.keySet())
            if (!ourSummaries.containsKey(k)) mismatches.add("summary " + k + ": missing in replay");
        for (var e : ourSummaries.entrySet()) {
            var l = legacySummaries.get(e.getKey());
            if (l == null) { mismatches.add("summary " + e.getKey() + ": not in legacy"); continue; }
            String[] f = {"totalcalls", "successfulcalls", "actualduration", "duration1", "customercost", "roundedduration"};
            for (int i = 0; i < f.length; i++)
                if (l[i].compareTo(e.getValue()[i]) != 0)
                    mismatches.add("summary " + e.getKey() + " " + f[i] + ": legacy=" + l[i] + " new=" + e.getValue()[i]);
        }

        // the composite SMS legacy could NOT rate (cdrerror): what the new pipeline does with them
        // Optional: -Dsms.parity.compositePlan=<plan id> replays legacy's cdrerror rows on that (composite) plan.
        var report = new StringBuilder();
        String compositePlan = System.getProperty("sms.parity.compositePlan");
        for (var le : compositePlan == null ? List.<Map<String, String>>of() : d.get("cdrerror")) {
            int partner = Integer.parseInt(le.get("InPartnerId"));
            var t = Tenant(d, Integer.parseInt(compositePlan), partner, 2, planById);
            var st = Rebind(new SmsTestData.InMemoryAccounting(null), t.Catalog());
            cdr c = SmsTestData.Sms(le.get("OriginatingCallingNumber"), le.get("OriginatingCalledNumber"), partner,
                    new BigDecimal(le.get("DurationSec")).intValue(), le.get("SequenceNumber"), Ts(le.get("StartTime")));
            var r = CdrPipeline.SmsOutgoing(t.Catalog(), st, LocalDateTime::now)
                    .Process(new CdrBatch(t.Mediation(), t.Partners, List.of(c), new SmsTestData.CapturingSql(), new CountingAutoIncrementManager(1)));
            report.append(String.format("  legacy cdrerror %s calling=%s -> %s%n", le.get("UniqueBillId"),
                    le.get("OriginatingCallingNumber"),
                    r.Rated().isEmpty() ? "errored: " + r.Errored().get(0).ErrorCode
                            : "rated " + r.Rated().get(0).Chargeables().get(0).Prefix + " @ " + r.Rated().get(0).Chargeables().get(0).BilledAmount.toPlainString()));
        }

        System.out.println("SMS LEGACY PARITY: replayed=" + txns.size() + " compared=" + compared
                + " accounts=" + store.Accounts.size() + " ledgerRows=" + store.Ledger.size()
                + " summaryKeys(legacy/new)=" + legacySummaries.size() + "/" + ourSummaries.size()
                + " mismatches=" + mismatches.size());
        mismatches.stream().limit(60).forEach(m -> System.out.println("  MISMATCH " + m));
        System.out.print(report);
        assertEquals(List.of(), mismatches);
    }

    // ── fixture building from the dump ─────────────────────────────────────────────────────────────

    private static SmsTestData.Tenant Tenant(Map<String, List<Map<String, String>>> d, int plan, int partner, int rule,
            Map<String, Map<String, String>> planById) {
        var t = new SmsTestData.Tenant();
        t.Rules.clear();
        for (var jr : d.get("jrule")) {
            Boolean col = jr.get("isPrepaid") == null ? null : !"0".equals(jr.get("isPrepaid"));
            var parsed = MySqlSmsAccountingStore.ParseRule(Integer.parseInt(jr.get("id")), jr.get("ruleName"), jr.get("JsonExpression"), col);
            if (parsed != null) t.Rules.put(parsed.Id(), parsed);
        }
        var rows = new ArrayList<TestData.Ra>();
        for (var r : d.get("rate")) {
            if (Integer.parseInt(r.get("idrateplan")) != plan) continue;
            var ra = TestData.Ra(0, r.get("rateamount")).idRatePlan(plan)
                    .resolution(Integer.parseInt(r.get("Resolution")))
                    .minDurationSec(Float.parseFloat(r.get("MinDurationSec")))
                    .surchargeTime(Integer.parseInt(r.get("SurchargeTime")))
                    .surchargeAmount(r.get("SurchargeAmount") != null ? r.get("SurchargeAmount") : "0")
                    .startdate(Ts(r.get("startdate")));
            if (r.get("enddate") != null) ra.enddate(Ts(r.get("enddate")));
            var row = ra.build();
            row.id = Long.parseLong(r.get("id"));
            row.Prefix = new String(Hex(r.get("hexprefix")), StandardCharsets.UTF_8);
            row.CountryCode = r.get("CountryCode");
            row.ProductId = Integer.parseInt(r.get("ProductId"));
            row.Category = Byte.parseByte(r.get("Category"));
            row.SubCategory = Byte.parseByte(r.get("SubCategory"));
            row.OtherAmount3 = r.get("OtherAmount3") != null ? new BigDecimal(r.get("OtherAmount3")) : null;
            row.OtherAmount9 = r.get("OtherAmount9") != null ? Float.parseFloat(r.get("OtherAmount9")) : null;
            row.billingspan = r.get("billingspan") != null ? Integer.parseInt(r.get("billingspan")) : null;
            row.RateAmountRoundupDecimal = r.get("RateAmountRoundupDecimal") != null ? Integer.parseInt(r.get("RateAmountRoundupDecimal")) : null;
            rows.add(ra);
        }
        var tup = t.Fixture.tup(10, AssignmentDirection.Customer.value, partner, null, 0, rows.toArray(new TestData.Ra[0]));
        t.Assignments.put(tup.id, new SmsBillingRuleCatalog.Assignment(tup.id, rule, 20));
        t.Partners.put(partner, new Partner(partner, null, 3));
        var p = planById.get(Integer.toString(plan));
        var rp = t.Fixture.dicRatePlan.get(Integer.toString(plan));
        rp.field4 = p.get("field4");
        rp.Currency = p.get("Currency");
        rp.BillingSpan = p.get("BillingSpan");
        rp.RateAmountRoundupDecimal = p.get("RateAmountRoundupDecimal") != null ? Integer.parseInt(p.get("RateAmountRoundupDecimal")) : null;
        t.Fixture.billingSpans.clear();
        for (var s : d.get("span")) {
            var e = new enumbillingspan();
            e.ofbiz_uom_Id = s.get("ofbiz_uom_Id");
            e.value = Long.parseLong(s.get("value"));
            t.Fixture.billingSpans.put(e.ofbiz_uom_Id, e);
        }
        return t;
    }

    /** Same accounts/ledger, new catalog (each replayed SMS has its own fixture tuple ids). */
    private static SmsTestData.InMemoryAccounting Rebind(SmsTestData.InMemoryAccounting old, SmsBillingRuleCatalog catalog) {
        var s = new SmsTestData.InMemoryAccounting(catalog);
        s.Accounts.putAll(old.Accounts);
        s.Ledger.addAll(old.Ledger);
        return s;
    }

    /** Apply the batch's account/ledger INSERTs to the in-memory DB (UPDATEs already mutated the locked objects). */
    private static void Apply(SmsTestData.InMemoryAccounting store, SmsTestData.CapturingSql sql) {
        for (String ins : sql.StartingWith("insert into account ("))
            for (var row : SmsTestData.InsertRows(ins)) {
                var a = store.AddAccount(Long.parseLong(row.get("id")), Unq(row.get("accountName")),
                        Integer.parseInt(row.get("idPartner")), row.get("balanceAfter"));
                a.balanceBefore = new BigDecimal(row.get("balanceBefore"));
                a.lastAmount = "null".equals(row.get("lastAmount")) ? null : new BigDecimal(row.get("lastAmount"));
            }
        for (String ins : sql.StartingWith("insert into acc_ledger_summary"))
            for (var row : SmsTestData.InsertRows(ins))
                store.AddLedger(Long.parseLong(row.get("id")), Long.parseLong(row.get("idAccount")),
                        Ts(Unq(row.get("transactionDate"))), row.get("AMOUNT"));
    }

    private static String NameOf(SmsTestData.InMemoryAccounting store, long id) {
        return store.Accounts.values().stream().filter(a -> a.id == id).map(a -> a.accountName).findFirst().orElse("?" + id);
    }

    private static void Fold(Map<String, BigDecimal[]> into, CdrSummaryType table, AbstractCdrSummary s) {
        String k = table + "|" + s.tup_starttime.format(TS) + "|" + s.tup_inpartnerid + "|" + s.tup_outpartnerid + "|"
                + s.tup_matchedprefixcustomer + "|" + s.tup_customerrate.stripTrailingZeros().toPlainString();
        Add(into, k, Long.toString(s.totalcalls), Long.toString(s.successfulcalls), s.actualduration.toPlainString(),
                s.duration1.toPlainString(), s.customercost.toPlainString(), s.roundedduration.toPlainString());
    }

    private static void Add(Map<String, BigDecimal[]> into, String key, String... values) {
        var acc = into.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        for (int i = 0; i < values.length; i++) acc[i] = acc[i].add(new BigDecimal(values[i] == null ? "0" : values[i]));
    }

    private void Eq(String key, String field, String legacy, String ours) {
        if (legacy == null ? ours != null : !legacy.equals(ours))
            mismatches.add(key + " " + field + ": legacy=" + legacy + " new=" + ours);
    }

    private void Num(String key, String field, String legacy, BigDecimal ours) {
        boolean bothNull = legacy == null && ours == null;
        boolean equal = legacy != null && ours != null && new BigDecimal(legacy).compareTo(ours) == 0;
        if (!bothNull && !equal) mismatches.add(key + " " + field + ": legacy=" + legacy + " new=" + ours);
    }

    private static Map<String, Map<String, String>> Index(List<Map<String, String>> rows, String key) {
        var m = new LinkedHashMap<String, Map<String, String>>();
        for (var r : rows) m.put(r.get(key), r);
        return m;
    }

    private static LocalDateTime Ts(String s) {
        return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s, TS);
    }

    private static String Unq(String sqlLiteral) {
        if (sqlLiteral == null || "null".equals(sqlLiteral)) return null;
        return sqlLiteral.startsWith("'") ? sqlLiteral.substring(1, sqlLiteral.length() - 1) : sqlLiteral;
    }

    private static byte[] Hex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
