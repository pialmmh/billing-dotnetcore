package com.telcobright.billing.testsupport;

import com.telcobright.billing.mediation.cdr.CdrBatch;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.account;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sms.ISmsAccountingStore;
import com.telcobright.billing.mediation.sms.SmsBillingRuleCatalog;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared fixture for the outgoing-SMS tests. ALL VALUES ARE SYNTHETIC (numbers, sender ids, partner/plan/rate ids,
 * rates); they reproduce the structure of a real SMS tenant's configuration — a composite-only plan, a composite +
 * destination-only plan with a tech prefix, a plain destination-only plan, legacy billing rules — without any
 * production data. Real-data parity is checked out of tree by {@code SmsLegacyParityRehearsal}.
 */
public final class SmsTestData {
    private SmsTestData() {}

    /** The Phase-1 Kafka record shape (synthetic values). */
    public static final String SampleJson = "{\n"
            + "  \"idCall\": \"1000000000000000001\",\n"
            + "  \"terminatingCallingNumber\": \"8809600000001\",\n"
            + "  \"terminatingCalledNumber\": \"8801700000001\",\n"
            + "  \"startTime\": \"2026-10-05 11:15:51\",\n"
            + "  \"endTime\": \"2026-10-05 11:15:51\",\n"
            + "  \"inPartnerId\": 9001,\n"
            + "  \"outPartnerId\": 9100,\n"
            + "  \"switchId\": 1,\n"
            + "  \"sequenceNumber\": 0,\n"
            + "  \"serviceGroup\": 1,\n"
            + "  \"originatingCallingNumber\": \"8809600000001\",\n"
            + "  \"originatingCalledNumber\": \"8801700000001\",\n"
            + "  \"durationSec\": 60,\n"
            + "  \"signalingStartTime\": \"2026-10-05 11:15:51\",\n"
            + "  \"message\": \"U3ludGhldGljIHRlc3QgbWVzc2FnZQ==\",\n"
            + "  \"campaignId\": 2,\n"
            + "  \"smsCount\": 1\n"
            + "}";

    /** Base64 of "Synthetic test message". */
    public static final String Message = "U3ludGhldGljIHRlc3QgbWVzc2FnZQ==";
    public static final String MaskSender = "BRAND";               // alphanumeric (masking) sender id
    public static final String NumericSender = "8809600000001";    // numeric (non-masking) sender
    public static final String Called = "8801700000001";

    public static final int PartnerA = 9001;   // plan 501: composite-only
    public static final int PartnerB = 9002;   // plan 502: composite + destination-only, tech prefix on the plan
    public static final int PartnerC = 9003;   // plan 504: destination-only 880
    public static final int OutPartner = 9100;

    public static final char US = '\u001F';
    public static final int PostpaidRule = 2;
    public static final int PrepaidRule = 1;

    /** One tenant's rating config + billing-rule catalog. */
    public static final class Tenant {
        public final TestData.Fixture Fixture = TestData.fixture();
        public final Map<Integer, SmsBillingRuleCatalog.Assignment> Assignments = new HashMap<>();
        public final Map<Integer, SmsBillingRuleCatalog.BillingRule> Rules = new HashMap<>();
        public final Map<Integer, Partner> Partners = new HashMap<>();

        public Tenant() {
            Rules.put(PostpaidRule, new SmsBillingRuleCatalog.BillingRule(PostpaidRule, "OnFirstDayOfEachMonth,ForPreviousMonth", false));
            Rules.put(PrepaidRule, new SmsBillingRuleCatalog.BillingRule(PrepaidRule, "Prepaid", true));
        }

        /** Assign {@code plan}'s rows to {@code partner} (idService=10, customer) with a billing rule bound to {@code sg}. */
        public int Assign(int partner, int plan, int rule, int sg, TestData.Ra... rows) {
            var t = Fixture.tup(10, AssignmentDirection.Customer.value, partner, null, 0, rows);
            Assignments.put(t.id, new SmsBillingRuleCatalog.Assignment(t.id, rule, sg));
            Fixture.dicRatePlan.get(Integer.toString(plan)).Currency = "BDT";
            Partners.put(partner, new Partner(partner, null, 3));
            return t.id;
        }

        public MediationContext Mediation() { return Fixture.mediation(); }

        public SmsBillingRuleCatalog Catalog() { return new SmsBillingRuleCatalog(Map.copyOf(Assignments), Map.copyOf(Rules)); }
    }

    /** One SMS rate row. */
    public static TestData.Ra Row(int plan, String prefix, String amount, int resolution, float minDurationSec,
            int surchargeTime, String surchargeAmount, String countryCode, long rateId, long productId, LocalDateTime start) {
        var ra = TestData.Ra(0, amount).idRatePlan(plan).resolution(resolution).minDurationSec(minDurationSec)
                .surchargeTime(surchargeTime).surchargeAmount(surchargeAmount).startdate(start);
        rate r = ra.build();
        r.Prefix = prefix;
        r.CountryCode = countryCode;
        r.id = rateId;
        r.ProductId = (int) productId;
        return ra;
    }

    private static final LocalDateTime PlanStart = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

    // ── the synthetic plan rows (surcharge-window plans mirror the live per-SMS plan shape) ─────────
    public static TestData.Ra P501Mask() {
        return Row(501, MaskSender + US + "8801", "0.70000000", 1, 1f, 60, "60", null, 5011, 7001, PlanStart);
    }
    public static TestData.Ra P501NonMask() {
        return Row(501, NumericSender + US + "8801", "0.20000000", 1, 1f, 60, "60", null, 5012, 7002, PlanStart);
    }
    public static TestData.Ra P502Plain() {
        return Row(502, "8801", "0.30000000", 1, 1f, 60, "60", null, 5021, 7003, PlanStart);
    }
    public static TestData.Ra P502Mask() {
        return Row(502, MaskSender + US + "8801", "0.70000000", 1, 1f, 60, "60", null, 5022, 7001, PlanStart);
    }
    public static TestData.Ra P503Plain() {
        return Row(503, "8801", "0.50000000", 1, 0f, 0, "60", "880", 5031, 7003, PlanStart);
    }
    public static TestData.Ra P504Plain() {
        return Row(504, "880", "0.45000000", 1, 0f, 0, "0", "880", 5041, 7004, PlanStart);
    }

    /** Plan 501 (composite-only: mask 0.70, numeric sender 0.20) for partner A, billing rule 2 bound to SG20. */
    public static Tenant Plan501ForA() {
        var t = new Tenant();
        t.Assign(PartnerA, 501, PostpaidRule, 20, P501Mask(), P501NonMask());
        return t;
    }

    /** Plan 502 (destination-only 8801 @ 0.30 + mask @ 0.70; tech prefix 8801 on the plan) for partner B. */
    public static Tenant Plan502ForB() {
        var t = new Tenant();
        t.Assign(PartnerB, 502, PostpaidRule, 20, P502Plain(), P502Mask());
        t.Fixture.dicRatePlan.get("502").field4 = "8801";
        return t;
    }

    /** Plan 503 (plain destination-only 8801 @ 0.50, no surcharge window) for partner A. */
    public static Tenant Plan503ForA() {
        var t = new Tenant();
        t.Assign(PartnerA, 503, PostpaidRule, 20, P503Plain());
        return t;
    }

    /** An SMS cdr shaped like the parser's output. */
    public static cdr Sms(String calling, String called, int inPartner, int durationSec, String idCall, LocalDateTime start) {
        cdr c = new cdr();
        c.UniqueBillId = idCall;
        c.SequenceNumber = Long.parseLong(idCall);
        c.FileName = "kafka:sms";
        c.SwitchId = 1;
        c.OriginatingCallingNumber = calling; c.TerminatingCallingNumber = calling;
        c.OriginatingCalledNumber = called; c.TerminatingCalledNumber = called;
        c.StartTime = start; c.AnswerTime = start; c.EndTime = start; c.SignalingStartTime = start;
        c.InPartnerId = inPartner; c.OutPartnerId = OutPartner;
        c.DurationSec = BigDecimal.valueOf(durationSec);
        c.ChargingStatus = durationSec > 0 ? 1 : 0;
        c.AdditionalMetaData = Message;
        c.ValidFlag = 1; c.PartialFlag = 0;
        return c;
    }

    // ── in-memory DB ──────────────────────────────────────────────────────────────────────────────

    /** Captures every statement (in order) — the SQL the batch would send. */
    public static final class CapturingSql implements ISqlExecutor {
        public final List<String> Statements = new ArrayList<>();
        @Override public int ExecuteNonQuery(String sql) { Statements.add(sql); return 1; }
        public List<String> StartingWith(String prefix) {
            return Statements.stream().filter(s -> s.startsWith(prefix)).toList();
        }
    }

    /** Accounts + ledger rows as the schema holds them; reads return the live objects (as a locked row would). */
    public static final class InMemoryAccounting implements ISmsAccountingStore {
        public final SmsBillingRuleCatalog Catalog;
        public final Map<String, account> Accounts = new LinkedHashMap<>();
        public final List<acc_ledger_summary> Ledger = new ArrayList<>();
        public int AccountLocks, LedgerLocks;

        public InMemoryAccounting(SmsBillingRuleCatalog catalog) { Catalog = catalog; }

        public account AddAccount(long id, String name, int partner, String balanceAfter) {
            account a = new account();
            a.id = id; a.accountName = name; a.idPartner = partner; a.serviceGroup = 20; a.serviceFamily = 10;
            a.billableType = "/billable"; a.uom = "BDT"; a.isBillable = 1; a.isCustomerAccount = 1;
            a.balanceAfter = new BigDecimal(balanceAfter); a.balanceBefore = BigDecimal.ZERO;
            Accounts.put(name, a);
            return a;
        }

        public acc_ledger_summary AddLedger(long id, long account, LocalDateTime day, String amount) {
            var l = new acc_ledger_summary();
            l.id = id; l.idAccount = account; l.transactionDate = day; l.AMOUNT = new BigDecimal(amount);
            Ledger.add(l);
            return l;
        }

        @Override public SmsBillingRuleCatalog LoadBillingRuleCatalog() { return Catalog; }

        @Override public Map<String, account> LockAccountsByName(Collection<String> names) {
            AccountLocks++;
            var m = new HashMap<String, account>();
            for (String n : names) if (Accounts.containsKey(n)) m.put(n, Accounts.get(n));
            return m;
        }

        @Override public List<acc_ledger_summary> LockLedgerRows(Collection<Long> ids, Collection<LocalDateTime> days) {
            LedgerLocks++;
            return Ledger.stream().filter(l -> ids.contains(l.idAccount) && days.contains(l.transactionDate)).toList();
        }
    }

    /** Run cdrs through the SMS pipeline over the in-memory DB. */
    public static CdrBatchResult RunSms(Tenant t, InMemoryAccounting store, CapturingSql sql, CountingAutoIncrementManager ids,
            LocalDateTime now, cdr... cdrs) {
        var pipeline = CdrPipeline.SmsOutgoing(t.Catalog(), store, () -> now);
        return pipeline.Process(new CdrBatch(t.Mediation(), t.Partners, List.of(cdrs), sql, ids));
    }

    /** Parse the single values tuple of a one-row INSERT into column -> raw SQL literal. */
    public static Map<String, String> InsertRow(String insertSql) {
        var rows = InsertRows(insertSql);
        if (rows.size() != 1) throw new IllegalStateException("expected one row, got " + rows.size());
        return rows.get(0);
    }

    /** Parse every values tuple of a (multi-row) INSERT into column -> raw SQL literal maps, in order. */
    public static List<Map<String, String>> InsertRows(String insertSql) {
        int open = insertSql.indexOf('(');
        int close = insertSql.indexOf(')', open);
        String[] cols = insertSql.substring(open + 1, close).split(",");
        String values = insertSql.substring(insertSql.indexOf(" values ", close) + " values ".length());
        var rows = new ArrayList<Map<String, String>>();
        var parts = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < values.length(); i++) {
            char ch = values.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (!quoted && ch == '(') { depth++; if (depth == 1) continue; }
            if (!quoted && ch == ')') {
                depth--;
                if (depth == 0) {
                    parts.add(cur.toString().trim()); cur.setLength(0);
                    var row = new LinkedHashMap<String, String>();
                    for (int c = 0; c < cols.length; c++) row.put(cols[c].trim(), parts.get(c));
                    rows.add(row); parts.clear();
                    continue;
                }
            }
            if (depth == 0) continue;                       // the "," between tuples
            if (ch == ',' && !quoted && depth == 1) { parts.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(ch);
        }
        return rows;
    }
}
