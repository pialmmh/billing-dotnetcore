package com.telcobright.billing.beans;

import com.telcobright.billing.data.MySqlSummaryBatchRunner;
import com.telcobright.billing.ingest.sms.SmsCdrEventParser;
import com.telcobright.billing.ingest.sms.SmsCdrKafkaConsumer;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryRollupOptions;
import com.telcobright.billing.testsupport.SmsTestData;
import com.telcobright.billing.testsupport.SmsTestData.CapturingSql;
import com.telcobright.billing.testsupport.SmsTestData.InMemoryAccounting;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pausing the summary roll-up ({@code billing.summary-rollup.enabled=false}, as on 2026-10-07) must leave SMS billing
 * untouched: the SMS intake and processor do not depend on the roll-up at all, and every SMS batch still writes its
 * cdr, accounting and {@code summary_affected} outbox row — the outbox row is what the roll-up folds once resumed.
 */
class SmsBillingWithRollupPausedTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 1, 30, 0);

    @Test
    void the_sms_intake_and_processor_do_not_depend_on_the_rollup() {
        for (Class<?> c : List.of(CdrProcessor.class, SmsCdrKafkaConsumer.class)) {
            for (var f : c.getDeclaredFields()) AssertNotRollup(c, f.getType(), "field " + f.getName());
            for (var k : c.getDeclaredConstructors())
                for (var p : k.getParameterTypes()) AssertNotRollup(c, p, "constructor parameter");
            for (var m : c.getDeclaredMethods())
                for (var p : m.getParameterTypes()) AssertNotRollup(c, p, "parameter of " + m.getName());
        }
    }

    private static void AssertNotRollup(Class<?> owner, Class<?> type, String where) {
        assertFalse(type == SummaryRollupOptions.class || type == SummaryRollupConsumer.class
                        || type == MySqlSummaryBatchRunner.class,
                () -> owner.getSimpleName() + " " + where + " is " + type.getSimpleName());
    }

    @Test
    void an_sms_batch_still_writes_cdr_accounting_and_its_outbox_row() {
        var tenant = SmsTestData.Plan501ForA();
        var parsed = new SmsCdrEventParser(0).Parse(SmsTestData.SampleJson);
        assertTrue(parsed.Ok(), () -> "dead-lettered: " + parsed.DeadLetterReason());
        cdr sms = parsed.Cdr();
        var sql = new CapturingSql();

        var result = SmsTestData.RunSms(tenant, new InMemoryAccounting(tenant.Catalog()), sql,
                new CountingAutoIncrementManager(1000), NOW, sms);

        assertEquals(1, result.Rated().size(), () -> "errored: " + result.Errored().stream().map(c -> c.ErrorCode).toList());
        assertEquals(1, sql.StartingWith("insert into cdr (").size());
        assertEquals(1, sql.StartingWith("insert into acc_chargeable (").size());
        assertEquals(1, sql.StartingWith("insert into acc_transaction (").size());
        assertEquals(1, sql.StartingWith("insert into account (").size());
        assertEquals(1, sql.StartingWith("insert into acc_ledger_summary").size());
        assertEquals(1, sql.StartingWith("insert into summary_affected").size(),
                "the outbox row a paused roll-up leaves in place and folds later");
    }
}
