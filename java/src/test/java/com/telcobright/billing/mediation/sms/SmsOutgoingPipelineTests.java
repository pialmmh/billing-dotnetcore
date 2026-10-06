package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.ingest.sms.SmsCdrEventParser;
import com.telcobright.billing.mediation.cdr.CdrBatch;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.testsupport.SmsTestData;
import com.telcobright.billing.testsupport.SmsTestData.CapturingSql;
import com.telcobright.billing.testsupport.SmsTestData.InMemoryAccounting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.telcobright.billing.testsupport.SmsTestData.Called;
import static com.telcobright.billing.testsupport.SmsTestData.InsertRow;
import static com.telcobright.billing.testsupport.SmsTestData.InsertRows;
import static com.telcobright.billing.testsupport.SmsTestData.MaskSender;
import static com.telcobright.billing.testsupport.SmsTestData.NumericSender;
import static com.telcobright.billing.testsupport.SmsTestData.PartnerA;
import static com.telcobright.billing.testsupport.SmsTestData.PartnerB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end outgoing SMS (Phase 1) over an in-memory DB: Kafka JSON → parser → {@code CdrPipeline.SmsOutgoing}
 * → SG20 / SF10 → calling+called composite rate → cdr + acc_chargeable + acc_transaction + account + ledger +
 * summary_affected. All data synthetic (see {@link SmsTestData}).
 */
class SmsOutgoingPipelineTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 5, 11, 15, 51);

    private record Run(CdrBatchResult Result, CapturingSql Sql, InMemoryAccounting Store) {
        cdr Cdr() { return Result.Rated().get(0).Cdr(); }
        acc_chargeable Chargeable() { return Result.Rated().get(0).Chargeables().get(0); }
        Map<String, String> Transaction() { return InsertRow(Sql.StartingWith("insert into acc_transaction (").get(0)); }
    }

    private static Run Bill(SmsTestData.Tenant t, InMemoryAccounting store, cdr... cdrs) {
        var sql = new CapturingSql();
        var r = SmsTestData.RunSms(t, store, sql, new CountingAutoIncrementManager(1000), NOW, cdrs);
        return new Run(r, sql, store);
    }

    private static Run Bill(SmsTestData.Tenant t, cdr... cdrs) {
        return Bill(t, new InMemoryAccounting(t.Catalog()), cdrs);
    }

    private static cdr FromJson(String json) {
        var p = new SmsCdrEventParser(0).Parse(json);
        assertTrue(p.Ok(), () -> "dead-lettered: " + p.DeadLetterReason());
        return p.Cdr();
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> message + ": expected " + expected + " but was " + actual);
    }

    // ───────────────────────────── the Kafka record ─────────────────────────────

    @Test
    void sample_json_non_masking_sender_rates_on_its_composite_row() {
        var run = Bill(SmsTestData.Plan501ForA(), FromJson(SmsTestData.SampleJson));

        assertEquals(1, run.Result().Rated().size(), () -> "errored: " + run.Result().Errored().stream().map(c -> c.ErrorCode).toList());
        cdr c = run.Cdr();
        acc_chargeable ch = run.Chargeable();
        assertEquals(20, c.ServiceGroup, "SG fixed by the SMS pipeline");
        assertEquals(10, ch.servicefamily);
        assertEquals((byte) 1, ch.assignedDirection);
        assertEquals(NumericSender + "|8801", ch.Prefix, "display form of the raw composite match");
        assertEquals(NumericSender + "|8801", c.MatchedPrefixCustomer);
        assertNull(c.MatchedPrefixSupplier, "SG20 has no supplier leg");
        assertEquals(5012L, ch.RateId, "matched the raw <numeric sender><0x1F>8801 row");
        assertAmount("0.20", ch.unitPriceOrCharge);
        assertAmount("0.20", ch.BilledAmount);
        assertAmount("60", ch.Quantity);
        assertAmount("0.20", c.InPartnerCost);
        assertAmount("0.20", c.CustomerRate);
        assertAmount("60", c.Duration1);
        assertNull(c.RoundedDuration, "no voice pulse rounding for SMS");
        assertEquals(SmsTestData.PostpaidRule, ch.idBillingrule);
        assertEquals("1000000000000000001", ch.uniqueBillId);
        assertEquals("BDT", ch.idBilledUom);
        assertEquals(1, c.MediationComplete);
        assertEquals(SmsTestData.Message, c.AdditionalMetaData);
        assertEquals(1, run.Sql().StartingWith("insert into cdr (").size());
        assertEquals(0, run.Sql().StartingWith("insert into cdrerror (").size());
        assertEquals(1, run.Sql().StartingWith("insert into summary_affected").size());
    }

    @Test
    void masking_sender_rates_on_its_composite_row() {
        var json = SmsTestData.SampleJson
                .replace("\"originatingCallingNumber\": \"" + NumericSender + "\"", "\"originatingCallingNumber\": \"" + MaskSender + "\"")
                .replace("\"terminatingCallingNumber\": \"" + NumericSender + "\"", "\"terminatingCallingNumber\": \"" + MaskSender + "\"");
        var run = Bill(SmsTestData.Plan501ForA(), FromJson(json));
        acc_chargeable ch = run.Chargeable();
        assertEquals(5011L, ch.RateId, "matched the raw BRAND<0x1F>8801 row");
        assertEquals(MaskSender + "|8801", ch.Prefix);
        assertAmount("0.70", ch.unitPriceOrCharge);
        assertAmount("0.70", ch.BilledAmount);
        assertEquals(20, run.Cdr().ServiceGroup);
    }

    @Test
    void a_composite_only_plan_has_no_fallback_so_another_sender_is_RATE_NOT_FOUND() {
        var run = Bill(SmsTestData.Plan501ForA(), SmsTestData.Sms("8809600000009", Called, PartnerA, 60, "1001", T));
        assertEquals(0, run.Result().Rated().size());
        assertTrue(run.Result().Errored().get(0).ErrorCode.startsWith("RATE_NOT_FOUND"));
        assertEquals(1, run.Sql().StartingWith("insert into cdrerror (").size());
        assertEquals(0, run.Sql().StartingWith("insert into acc_transaction").size(), "no accounting for an unrated SMS");
    }

    // ───────────────────────────── fallback / tech prefix ─────────────────────────────

    @Test
    void destination_only_8801_is_the_fallback_and_the_plan_tech_prefix_is_ignored() {
        var nonMask = Bill(SmsTestData.Plan502ForB(), SmsTestData.Sms("8809600000007", Called, PartnerB, 60, "2001", T));
        assertEquals("8801", nonMask.Chargeable().Prefix);
        assertAmount("0.30", nonMask.Chargeable().BilledAmount);

        var mask = Bill(SmsTestData.Plan502ForB(), SmsTestData.Sms(MaskSender, Called, PartnerB, 60, "2002", T));
        assertEquals(MaskSender + "|8801", mask.Chargeable().Prefix, "BRAND<0x1F>8801 (9) beats 8801 (4)");
        assertAmount("0.70", mask.Chargeable().BilledAmount);

        var lower = Bill(SmsTestData.Plan502ForB(), SmsTestData.Sms("brand", Called, PartnerB, 60, "2003", T));
        assertEquals("8801", lower.Chargeable().Prefix, "case-sensitive sender: falls back");
    }

    // ───────────────────────────── smsCount never affects billing ─────────────────────────────

    /** The sample record with {@code durationSec} and {@code smsCount} replaced (null smsCount = field absent). */
    private static String Record(int durationSec, String smsCount) {
        String j = SmsTestData.SampleJson.replace("\"durationSec\": 60", "\"durationSec\": " + durationSec);
        return smsCount == null ? j.replace(",\n  \"smsCount\": 1", "") : j.replace("\"smsCount\": 1", "\"smsCount\": " + smsCount);
    }

    /** REGRESSION: durationSec=60 with smsCount=3 is billed as ONE unit — smsCount is not a multipart count. */
    @Test
    void durationSec_60_with_smsCount_3_bills_exactly_one_unit() {
        var run = Bill(SmsTestData.Plan501ForA(), FromJson(Record(60, "3")));
        assertEquals(1, run.Result().Rated().size(), "accepted and rated");
        cdr c = run.Cdr();
        acc_chargeable ch = run.Chargeable();
        assertAmount("60", c.DurationSec);
        assertAmount("60", c.Duration1);
        assertAmount("60", ch.Quantity, "billing quantity = durationSec, never smsCount");
        assertAmount("0.20", ch.unitPriceOrCharge);
        assertAmount("0.20", ch.BilledAmount, "1 unit x rate");
        assertAmount("0.20", c.InPartnerCost);
        assertAmount("-0.20", new BigDecimal(run.Transaction().get("amount")));
        assertAmount("-0.20", new BigDecimal(InsertRow(run.Sql().StartingWith("insert into account (").get(0)).get("balanceAfter")));
        var day = new com.telcobright.billing.mediation.summary.CdrSummaryContext(null, new CountingAutoIncrementManager(1))
                .GenerateSummary(c, run.Result().Rated().get(0).Customer())
                .get(com.telcobright.billing.mediation.engine.models.CdrSummaryType.sum_voice_day_01);
        assertEquals(1, day.totalcalls);
        assertAmount("60", day.actualduration);
        assertAmount("60", day.duration1);
        assertAmount("0.20", day.customercost);
    }

    /** Every billing output is identical whatever smsCount says (absent, 0, 1, 3, malformed): 120 s = 2 units. */
    @Test
    void smsCount_never_changes_any_billing_output() {
        String baseline = Fingerprint(Bill(SmsTestData.Plan501ForA(), FromJson(Record(120, null))));
        assertTrue(baseline.contains("BilledAmount=0.4|"), baseline);
        for (String smsCount : new String[] {"0", "1", "3", "\"two\""}) {
            String actual = Fingerprint(Bill(SmsTestData.Plan501ForA(), FromJson(Record(120, smsCount))));
            assertEquals(baseline, actual, "smsCount " + smsCount + " changed the bill");
        }
    }

    private static String Fingerprint(Run run) {
        cdr c = run.Cdr();
        acc_chargeable ch = run.Chargeable();
        var tx = run.Transaction();
        var acc = InsertRow(run.Sql().StartingWith("insert into account (").get(0));
        var day = new com.telcobright.billing.mediation.summary.CdrSummaryContext(null, new CountingAutoIncrementManager(1))
                .GenerateSummary(c, run.Result().Rated().get(0).Customer())
                .get(com.telcobright.billing.mediation.engine.models.CdrSummaryType.sum_voice_day_01);
        return "SG=" + c.ServiceGroup + "|DurationSec=" + N(c.DurationSec) + "|Duration1=" + N(c.Duration1)
                + "|InPartnerCost=" + N(c.InPartnerCost) + "|CustomerRate=" + N(c.CustomerRate)
                + "|MatchedPrefixCustomer=" + c.MatchedPrefixCustomer
                + "|SF=" + ch.servicefamily + "|Quantity=" + N(ch.Quantity) + "|BilledAmount=" + N(ch.BilledAmount)
                + "|unitPrice=" + N(ch.unitPriceOrCharge) + "|Prefix=" + ch.Prefix + "|RateId=" + ch.RateId
                + "|rule=" + ch.idBillingrule + "|txAmount=" + N(new BigDecimal(tx.get("amount")))
                + "|txBefore=" + N(new BigDecimal(tx.get("BalanceBefore"))) + "|txAfter=" + N(new BigDecimal(tx.get("BalanceAfter")))
                + "|accountAfter=" + N(new BigDecimal(acc.get("balanceAfter")))
                + "|sumCalls=" + day.totalcalls + "|sumActual=" + N(day.actualduration) + "|sumDuration1=" + N(day.duration1)
                + "|sumCost=" + N(day.customercost);
    }

    private static String N(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    // ───────────────────────────── billing units = durationSec / 60 ─────────────────────────────

    @Test
    void billing_units_on_a_surcharge_plan_charge_units_times_rate_with_meaningful_quantity() {
        for (int units = 1; units <= 3; units++) {
            var run = Bill(SmsTestData.Plan501ForA(), SmsTestData.Sms(MaskSender, Called, PartnerA, 60 * units, "300" + units, T));
            BigDecimal expected = new BigDecimal("0.70").multiply(BigDecimal.valueOf(units));
            assertEquals(0, expected.compareTo(run.Chargeable().BilledAmount), "units=" + units);
            assertAmount(Integer.toString(60 * units), run.Chargeable().Quantity);
            assertAmount(Integer.toString(60 * units), run.Cdr().Duration1);
            assertNull(run.Cdr().RoundedDuration);
            assertAmount(expected.negate().toPlainString(), new BigDecimal(run.Transaction().get("amount")));
        }
    }

    @Test
    void billing_units_on_a_plain_plan_charge_units_times_rate() {
        for (int units = 1; units <= 3; units++) {
            var run = Bill(SmsTestData.Plan503ForA(), SmsTestData.Sms(NumericSender, Called, PartnerA, 60 * units, "400" + units, T));
            BigDecimal expected = new BigDecimal("0.50").multiply(BigDecimal.valueOf(units));
            assertEquals(0, expected.compareTo(run.Chargeable().BilledAmount), "units=" + units);
            assertAmount(Integer.toString(60 * units), run.Chargeable().Quantity);
            assertAmount(Integer.toString(60 * units), run.Cdr().Duration1);
        }
    }

    // ───────────────────────────── accounting (legacy-equivalent) ─────────────────────────────

    /**
     * An existing postpaid account (balance −1.50) and an existing ledger row for the day (−1.50): a 0.50 SMS must
     * reproduce every legacy accounting field — chargeable, transaction (−0.50, −1.50 → −2.00), account update,
     * ledger merge, cdr meta totals.
     */
    @Test
    void reproduces_legacy_sms_accounting_on_an_existing_account() {
        var t = SmsTestData.Plan503ForA();
        var store = new InMemoryAccounting(t.Catalog());
        store.AddAccount(70, "d0/sg20/p" + PartnerA + "/sf10/pd0/billable/uomBDT", PartnerA, "-1.50");
        LocalDateTime day = LocalDateTime.of(2026, 10, 4, 0, 0);
        store.AddLedger(280, 70, day, "-1.50");
        LocalDateTime when = LocalDateTime.of(2026, 10, 4, 16, 16, 24);
        var run = Bill(t, store, SmsTestData.Sms(NumericSender, "8801700000002", PartnerA, 60, "1000000000000000112", when));

        cdr c = run.Cdr();
        acc_chargeable ch = run.Chargeable();
        // acc_chargeable
        assertEquals("1000000000000000112", ch.uniqueBillId);
        assertEquals(c.IdCall, ch.idEvent);
        assertEquals(when, ch.transactionTime);
        assertEquals((byte) 1, ch.assignedDirection);
        assertEquals("nc", ch.description);
        assertEquals(70L, ch.glAccountId);
        assertEquals(20, ch.servicegroup);
        assertEquals(10, ch.servicefamily);
        assertEquals(7003L, ch.ProductId);
        assertEquals("BDT", ch.idBilledUom);
        assertEquals("TF_s", ch.idQuantityUom);
        assertAmount("0.50", ch.BilledAmount);
        assertAmount("60", ch.Quantity);
        assertAmount("0.50", ch.unitPriceOrCharge);
        assertEquals("8801", ch.Prefix);
        assertEquals(5031L, ch.RateId);
        assertAmount("0", ch.TaxAmount1);
        assertEquals(SmsTestData.PostpaidRule, ch.idBillingrule);
        // cdr
        assertEquals("880", c.CountryCode);
        assertAmount("0.50", c.CustomerRate);
        assertAmount("0.50", c.InPartnerCost);
        assertEquals("8801", c.MatchedPrefixCustomer);
        assertAmount("60", c.Duration1);
        assertAmount("0.50", c.ChargeableMetaTotal);
        assertAmount("-0.50", c.TransactionMetaTotal);
        assertAmount("120", c.SummaryMetaTotal);
        assertEquals(1, c.MediationComplete);
        assertEquals(0f, c.PDD);
        assertEquals(0, c.NERSuccess);
        // acc_transaction
        var tx = run.Transaction();
        assertEquals("'c'", tx.get("debitOrCredit"));
        assertEquals("'1000000000000000112'", tx.get("uniqueBillId"));
        assertEquals(Long.toString(c.IdCall), tx.get("idEvent"));
        assertEquals("'nc'", tx.get("description"));
        assertEquals("70", tx.get("glAccountId"));
        assertEquals("'BDT'", tx.get("uomId"));
        assertAmount("-0.50", new BigDecimal(tx.get("amount")));
        assertAmount("-1.50", new BigDecimal(tx.get("BalanceBefore")));
        assertAmount("-2.00", new BigDecimal(tx.get("BalanceAfter")));
        assertEquals("1", tx.get("isBillable"));
        assertEquals("null", tx.get("isPrepaid"));
        assertEquals("null", tx.get("isBilled"));
        assertEquals("'2026-10-04 16:16:24'", tx.get("transactionTime"));
        // account: −1.50 → −2.00
        var upd = run.Sql().StartingWith("update account set");
        assertEquals(1, upd.size());
        assertTrue(upd.get(0).contains("balanceBefore=-1.50") && upd.get(0).contains("lastAmount=-0.50")
                && upd.get(0).contains("balanceAfter=-2.00") && upd.get(0).endsWith("where id=70"), upd.get(0));
        assertEquals(0, run.Sql().StartingWith("insert into account").size(), "the existing account is reused");
        // ledger (account, day): −1.50 → −2.00, merged into the existing row
        var led = run.Sql().StartingWith("update acc_ledger_summary set");
        assertEquals(1, led.size());
        assertTrue(led.get(0).contains("AMOUNT=-2.00") && led.get(0).contains("idAccount=70") && led.get(0).contains("id=280"), led.get(0));
        assertEquals(0, run.Sql().StartingWith("insert into acc_ledger_summary").size());
    }

    @Test
    void a_new_partner_account_is_created_with_the_legacy_name_and_flags() {
        var run = Bill(SmsTestData.Plan501ForA(), FromJson(SmsTestData.SampleJson));
        var acc = InsertRow(run.Sql().StartingWith("insert into account (").get(0));
        assertEquals("'d0/sg20/p" + PartnerA + "/sf10/pd0/billable/uomBDT'", acc.get("accountName"));
        assertEquals(Integer.toString(PartnerA), acc.get("idPartner"));
        assertEquals("20", acc.get("serviceGroup"));
        assertEquals("10", acc.get("serviceFamily"));
        assertEquals("0", acc.get("product"));
        assertEquals("'/billable'", acc.get("billableType"));
        assertEquals("'BDT'", acc.get("uom"));
        assertEquals("0", acc.get("Depth"));
        assertEquals("1", acc.get("isBillable"));
        assertEquals("1", acc.get("isCustomerAccount"));
        assertAmount("-0.20", new BigDecimal(acc.get("balanceAfter")));
        assertAmount("0", new BigDecimal(acc.get("negativeBalanceLimit")));
        assertEquals(acc.get("id"), Long.toString(run.Chargeable().glAccountId));
        var led = InsertRow(run.Sql().StartingWith("insert into acc_ledger_summary").get(0));
        assertEquals(acc.get("id"), led.get("idAccount"));
        assertEquals("'2026-10-05 00:00:00'", led.get("transactionDate"));
        assertAmount("-0.20", new BigDecimal(led.get("AMOUNT")));
    }

    @Test
    void balances_chain_in_batch_order_and_one_ledger_row_per_account_day() {
        var run = Bill(SmsTestData.Plan503ForA(),
                SmsTestData.Sms(NumericSender, "8801700000003", PartnerA, 60, "5001", T),
                SmsTestData.Sms(NumericSender, "8801700000004", PartnerA, 60, "5002", T.plusMinutes(1)),
                SmsTestData.Sms(NumericSender, "8801700000005", PartnerA, 120, "5003", T.plusMinutes(2)));
        var txs = InsertRows(run.Sql().StartingWith("insert into acc_transaction (").get(0));
        assertEquals(3, txs.size());
        assertAmount("0", new BigDecimal(txs.get(0).get("BalanceBefore")));
        assertAmount("-0.50", new BigDecimal(txs.get(0).get("BalanceAfter")));
        assertAmount("-0.50", new BigDecimal(txs.get(1).get("BalanceBefore")));
        assertAmount("-1.00", new BigDecimal(txs.get(1).get("BalanceAfter")));
        assertAmount("-1.00", new BigDecimal(txs.get(2).get("BalanceBefore")));
        assertAmount("-2.00", new BigDecimal(txs.get(2).get("BalanceAfter")), "the 2-part SMS debits 1.00");
        var ledger = InsertRows(run.Sql().StartingWith("insert into acc_ledger_summary").get(0));
        assertEquals(1, ledger.size(), "same account, same day -> one ledger row");
        assertAmount("-2.00", new BigDecimal(ledger.get(0).get("AMOUNT")));
        var acc = InsertRows(run.Sql().StartingWith("insert into account (").get(0));
        assertEquals(1, acc.size());
        assertAmount("-2.00", new BigDecimal(acc.get(0).get("balanceAfter")));
    }

    @Test
    void a_prepaid_rule_posts_to_the_customer_account_and_flags_the_transaction() {
        var t = new SmsTestData.Tenant();
        t.Assign(PartnerA, 503, SmsTestData.PrepaidRule, 20, SmsTestData.P503Plain());
        var run = Bill(t, SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "6001", T));
        var acc = InsertRow(run.Sql().StartingWith("insert into account (").get(0));
        assertEquals("'d0/sg20/p" + PartnerA + "/sf10/pd0/customer/uomBDT'", acc.get("accountName"));
        assertEquals("null", acc.get("isBillable"));
        assertEquals("1", acc.get("isCustomerAccount"));
        var tx = run.Transaction();
        assertEquals("1", tx.get("isPrepaid"));
        assertEquals("1", tx.get("isBilled"));
    }

    // ───────────────────────────── fixed SG20 ─────────────────────────────

    @Test
    void every_sms_is_SG20_whatever_the_partner_type_never_SG10_SG11_or_SG15() {
        for (Integer type : new Integer[] {1, 2, 3, 4, 5, 6, null}) {
            var t = SmsTestData.Plan503ForA();
            t.Partners.put(PartnerA, new Partner(PartnerA, null, type));
            var run = Bill(t, SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "7001", T));
            assertEquals(1, run.Result().Rated().size(), "partner type " + type);
            assertEquals(20, run.Cdr().ServiceGroup, "partner type " + type);
        }
        var t = SmsTestData.Plan503ForA();
        t.Partners.clear();                                   // partner unknown to the registry
        var run = Bill(t, SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "7002", T));
        assertEquals(20, run.Result().Rated().get(0).Cdr().ServiceGroup);

        var intl = Bill(SmsTestData.Plan503ForA(), SmsTestData.Sms(NumericSender, "0085212345678", PartnerA, 60, "7003", T));
        assertEquals(20, intl.Result().Errored().get(0).ServiceGroup, "a 00 destination is still SG20 (never SG15)");
    }

    @Test
    void the_billing_rule_SG_gate_rejects_an_SG1_tuple_and_accepts_an_SG20_tuple() {
        var sg1 = new SmsTestData.Tenant();
        sg1.Assign(PartnerA, 503, SmsTestData.PostpaidRule, 1, SmsTestData.P503Plain());   // billingruleassignment.idServiceGroup = 1
        var rejected = Bill(sg1, SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "8001", T));
        assertTrue(rejected.Result().Errored().get(0).ErrorCode.startsWith("RATE_NOT_FOUND"),
                "SG20 never rates against a tuple bound to another service group (no SG1 fallback)");

        var accepted = Bill(SmsTestData.Plan503ForA(), SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "8003", T));
        assertEquals(1, accepted.Result().Rated().size(), "the same plan bound to SG20 rates");
    }

    @Test
    void a_missing_billing_rule_routes_the_sms_to_cdrerror() {
        var t = new SmsTestData.Tenant();
        t.Assign(PartnerA, 503, 99, 20, SmsTestData.P503Plain());    // rule 99 does not exist
        var run = Bill(t, SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "8002", T));
        assertEquals(0, run.Result().Rated().size());
        assertTrue(run.Result().Errored().get(0).ErrorCode.contains("Billing rule"), run.Result().Errored().get(0).ErrorCode);
        assertEquals(0, run.Sql().StartingWith("insert into acc_transaction").size());
    }

    @Test
    void error_rows_fit_the_sms_cdrerror_columns() {
        var t = new SmsTestData.Tenant();
        var c = SmsTestData.Sms(NumericSender, Called, PartnerA, 60, "9001", T);
        c.AdditionalMetaData = "A".repeat(400);               // a long Base64 body
        c.OriginatingCalledNumber = "8801" + "7".repeat(200);
        var run = Bill(t, c);
        cdr e = run.Result().Errored().get(0);
        assertTrue(e.ErrorCode.length() <= 100, "cdrerror.ErrorCode is varchar(100) on the SMS schema");
        assertEquals(100, e.AdditionalMetaData.length(), "cdrerror.AdditionalMetaData is varchar(100) on the SMS schema");
        assertEquals(0, e.MediationComplete);
    }

    // ───────────────────────────── voice isolation ─────────────────────────────

    @Test
    void the_voice_pipeline_on_the_same_record_is_unchanged_sg10_and_called_only() {
        var t = SmsTestData.Plan501ForA();
        var sql = new CapturingSql();
        cdr c = FromJson(SmsTestData.SampleJson);
        var r = CdrPipeline.Default().Process(new CdrBatch(t.Mediation(), t.Partners, List.of(c), sql));
        assertEquals(10, c.ServiceGroup, "voice detection still makes a type-3 in-partner SG10");
        assertEquals(0, r.Rated().size(), "voice matches the CALLED number only: plan 501 has no destination-only row");
        assertEquals(0, sql.StartingWith("insert into acc_transaction").size(), "voice posts no accounting");
        assertEquals(0, sql.StartingWith("insert into account").size());
    }
}
