package com.telcobright.billing.mediation.summary;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;

/**
 * The outbox-consumer roll-up ({@link SummaryRollup}): decoded {@link Entry} rows fold into the sum_voice
 * tables BY service group and TUPLE-wise. Uses the same in-memory {@link ISummaryStore} fake as
 * {@link CdrSummaryContextTests} (captures the emitted SQL). Proves SG routing (SG10 -> day_03/hr_03),
 * in-page tuple merge (two identical calls -> ONE row), failed-leg skip, and a 'subtract' correction.
 */
class SummaryRollupTests {

    private static final class InMemorySummaryStore implements ISummaryStore {
        private final Map<CdrSummaryType, List<AbstractCdrSummary>> _rows = new HashMap<>();
        final List<String> ExecutedSql = new ArrayList<>();

        void Seed(CdrSummaryType table, AbstractCdrSummary row) {
            _rows.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
        }

        @Override
        public List<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, Collection<LocalDateTime> startTimes) {
            List<AbstractCdrSummary> list = _rows.get(table);
            if (list == null) return new ArrayList<>();
            return list.stream().filter(r -> startTimes.contains(r.tup_starttime)).toList();
        }

        @Override
        public int ExecuteNonQuery(String sql) { ExecutedSql.add(sql); return 1; }

        long CountSqlStartingWith(String prefix) {
            return ExecutedSql.stream().filter(s -> s.startsWith(prefix)).count();
        }
    }

    private static cdr Sg10Cdr() {
        cdr c = new cdr();
        c.SwitchId = 1; c.InPartnerId = 5; c.OutPartnerId = 7; c.IncomingRoute = "in"; c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.StartTime = LocalDateTime.of(2026, 6, 19, 14, 30, 0); c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60); c.RoundedDuration = BigDecimal.valueOf(60);
        c.CountryCode = "880"; c.AnsIdTerm = 42; c.MatchedPrefixSupplier = "1712";
        return c;
    }

    private static acc_chargeable Sg10Charge() {
        acc_chargeable c = new acc_chargeable();
        c.servicegroup = 10; c.servicefamily = 10;
        c.BilledAmount = new BigDecimal("1.0"); c.TaxAmount1 = new BigDecimal("0.5");
        c.Prefix = "1712"; c.unitPriceOrCharge = new BigDecimal("1.0"); c.idBilledUom = "BDT";
        return c;
    }

    // Customer() falls back to the first leg when no leg is direction-tagged, so a single-leg entry summarises it.
    private static Entry Entry10() {
        return new Entry(Sg10Cdr(), List.of(Sg10Charge()));
    }

    private static SummaryRollup.OutboxRow addRow(long id, Entry... entries) {
        return new SummaryRollup.OutboxRow(id, SummaryOutboxWriter.OpAdd, List.of(entries));
    }

    @Test
    void add_row_folds_a_sg10_call_into_day03_and_hr03() {
        var store = new InMemorySummaryStore();
        int folded = SummaryRollup.Apply(store, new CountingAutoIncrementManager(1000),
                List.of(addRow(1, Entry10())));

        assertEquals(1, folded);
        assertEquals(1, store.CountSqlStartingWith("insert into sum_voice_day_03"));
        assertEquals(1, store.CountSqlStartingWith("insert into sum_voice_hr_03"));
        assertEquals(2, store.ExecutedSql.size());                // exactly the two SG10 target tables
    }

    @Test
    void two_identical_calls_in_one_sweep_merge_tuple_wise_into_one_row() {
        var store = new InMemorySummaryStore();
        // two calls, same tuple + same bucket, across two outbox rows folded in ONE sweep
        int folded = SummaryRollup.Apply(store, new CountingAutoIncrementManager(1000),
                List.of(addRow(1, Entry10()), addRow(2, Entry10())));

        assertEquals(2, folded);
        // merged in-cache -> ONE insert per table (not two); the count accumulates inside that single row
        assertEquals(1, store.CountSqlStartingWith("insert into sum_voice_day_03"));
        assertEquals(1, store.CountSqlStartingWith("insert into sum_voice_hr_03"));
    }

    @Test
    void entry_without_a_customer_leg_is_skipped() {
        var store = new InMemorySummaryStore();
        int folded = SummaryRollup.Apply(store, new CountingAutoIncrementManager(1000),
                List.of(addRow(1, new Entry(Sg10Cdr(), List.of()))));   // no chargeable -> no customer leg

        assertEquals(0, folded);
        assertTrue(store.ExecutedSql.isEmpty());
    }

    @Test
    void subtract_row_negates_off_an_existing_bucket() {
        var cdr = Sg10Cdr();
        var charge = Sg10Charge();
        // existing persisted rows: two calls already summed on (ids 500/501)
        var existingDay = CdrSummaryBuilder.Build(cdr, charge, SummaryBucket.Day);
        existingDay.id = 500; existingDay.totalcalls = 2;
        var existingHr = CdrSummaryBuilder.Build(cdr, charge, SummaryBucket.Hour);
        existingHr.id = 501; existingHr.totalcalls = 2;

        var store = new InMemorySummaryStore();
        store.Seed(CdrSummaryType.sum_voice_day_03, existingDay);
        store.Seed(CdrSummaryType.sum_voice_hr_03, existingHr);

        var rows = List.of(new SummaryRollup.OutboxRow(9, SummaryOutboxWriter.OpSubtract, List.of(Entry10())));
        int folded = SummaryRollup.Apply(store, new CountingAutoIncrementManager(1000), rows);

        assertEquals(1, folded);
        // subtract negates onto the loaded row -> UPDATE (not insert)
        assertTrue(store.ExecutedSql.stream().anyMatch(
                s -> s.startsWith("update sum_voice_day_03") && s.contains("where id=500")));
        assertEquals(0, store.CountSqlStartingWith("insert into sum_voice_day_03"));
        assertEquals(1L, existingDay.totalcalls);                 // 2 - 1 (merged onto the same cached row)
    }
}
