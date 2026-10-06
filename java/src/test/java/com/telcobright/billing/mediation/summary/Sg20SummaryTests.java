package com.telcobright.billing.mediation.summary;

import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_01;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_01;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.testsupport.SmsTestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SG20 → sum_voice_day_01 / sum_voice_hr_01 with the legacy SgDomSmsOffnetOut field set (synthetic data). */
class Sg20SummaryTests {

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> message + ": expected " + expected + " but was " + actual);
    }

    @Test
    void sg20_folds_into_the_01_tables_with_the_legacy_field_set() {
        // shape of a legacy sum_voice_day_01 row: 1 SMS, 880 destination-only rate, actual 60, duration1 60, rounded 0
        var t = new SmsTestData.Tenant();
        t.Assign(SmsTestData.PartnerC, 504, SmsTestData.PostpaidRule, 20, SmsTestData.P504Plain());
        LocalDateTime when = LocalDateTime.of(2026, 10, 4, 13, 24, 14);
        var store = new SmsTestData.InMemoryAccounting(t.Catalog());
        var sql = new SmsTestData.CapturingSql();
        var r = SmsTestData.RunSms(t, store, sql, new CountingAutoIncrementManager(1), when,
                SmsTestData.Sms("8809600000002", "8801700000005", SmsTestData.PartnerC, 60, "123456789", when));
        assertEquals(1, r.Rated().size());

        var outbox = sql.StartingWith("insert into summary_affected").get(0);
        assertTrue(outbox.contains("'cdr'"));

        var rated = r.Rated().get(0);
        var tables = new CdrSummaryContext(null, new CountingAutoIncrementManager(1)).GenerateSummary(rated.Cdr(), rated.Customer());
        assertEquals(2, tables.size(), "SG20 folds into exactly the day + hour _01 tables");
        var day = tables.get(CdrSummaryType.sum_voice_day_01);
        var hr = tables.get(CdrSummaryType.sum_voice_hr_01);
        assertInstanceOf(sum_voice_day_01.class, day);
        assertInstanceOf(sum_voice_hr_01.class, hr);

        assertEquals(LocalDateTime.of(2026, 10, 4, 0, 0), day.tup_starttime);
        assertEquals(LocalDateTime.of(2026, 10, 4, 13, 0), hr.tup_starttime);
        assertEquals(1, day.tup_switchid);
        assertEquals(SmsTestData.PartnerC, day.tup_inpartnerid);
        assertEquals(SmsTestData.OutPartner, day.tup_outpartnerid);
        assertEquals("880", day.tup_matchedprefixcustomer);
        assertEquals("880", day.tup_countryorareacode);
        assertAmount("0.45", day.tup_customerrate);
        assertEquals("BDT", day.tup_customercurrency);
        assertEquals("BDT", day.tup_suppliercurrency);
        assertEquals("BDT", day.tup_tax1currency);
        assertEquals("BDT", day.tup_tax2currency);
        assertEquals("BDT", day.tup_vatcurrency);
        assertEquals(1, day.totalcalls);
        assertEquals(0, day.connectedcalls, "no ConnectTime on SMS (legacy: 0)");
        assertEquals(1, day.successfulcalls);
        assertAmount("60", day.actualduration);
        assertAmount("0", day.roundedduration, "RoundedDuration NULL -> 0, as legacy");
        assertAmount("60", day.duration1);
        assertAmount("0.45", day.customercost);
        assertAmount("0", day.suppliercost);
        assertAmount("0", day.tax1);
        assertAmount("0", day.tax2);
        assertAmount("0", day.vat);
        assertAmount("0", day.longDecimalAmount1);
    }

    @Test
    void the_display_composite_prefix_reaches_the_summary() {
        var t = SmsTestData.Plan501ForA();
        LocalDateTime when = LocalDateTime.of(2026, 10, 5, 11, 15, 51);
        var r = SmsTestData.RunSms(t, new SmsTestData.InMemoryAccounting(t.Catalog()), new SmsTestData.CapturingSql(),
                new CountingAutoIncrementManager(1), when,
                SmsTestData.Sms(SmsTestData.MaskSender, SmsTestData.Called, SmsTestData.PartnerA, 120, "42", when));
        var rated = r.Rated().get(0);
        var day = new CdrSummaryContext(null, new CountingAutoIncrementManager(1))
                .GenerateSummary(rated.Cdr(), rated.Customer()).get(CdrSummaryType.sum_voice_day_01);
        assertEquals(SmsTestData.MaskSender + "|8801", day.tup_matchedprefixcustomer);
        assertAmount("1.40", day.customercost);
        assertAmount("120", day.duration1);
        assertAmount("240", rated.Cdr().SummaryMetaTotal, "Σ actualduration over the day + hour rows");
    }

    @Test
    void the_outbox_encoding_round_trips_an_sg20_call() {
        var t = SmsTestData.Plan501ForA();
        LocalDateTime when = LocalDateTime.of(2026, 10, 5, 11, 15, 51);
        var r = SmsTestData.RunSms(t, new SmsTestData.InMemoryAccounting(t.Catalog()), new SmsTestData.CapturingSql(),
                new CountingAutoIncrementManager(1), when,
                SmsTestData.Sms(SmsTestData.MaskSender, SmsTestData.Called, SmsTestData.PartnerA, 60, "43", when));
        var entries = SummaryOutboxWriter.Decode(SummaryOutboxWriter.Encode(r.Rated()));
        assertEquals(1, entries.size());
        assertEquals(20, entries.get(0).Customer().servicegroup);
        assertEquals(SmsTestData.MaskSender + "|8801", entries.get(0).Customer().Prefix);
    }
}
