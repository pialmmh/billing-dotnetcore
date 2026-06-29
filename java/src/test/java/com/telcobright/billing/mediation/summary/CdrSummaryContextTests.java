// Faithful port of tests/Billing.Tests/CdrSummaryContextTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (CdrSummaryContext) per RULE T0.
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

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * The full summary orchestration end to end over an in-memory store: PopulatePrevSummary -> GenerateSummary
 * -> MergeAdd -> WriteAllChanges. A fresh call inserts day+hr rows; a call whose day row already exists
 * merges onto it and updates.
 */
class CdrSummaryContextTests {

    // The C# in-memory ISummaryStore fake: seeds existing rows, filters them on LoadByStartTimes, and
    // captures emitted SQL on ExecuteNonQuery.
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
    }

    private static cdr Sg10Cdr() {
        cdr c = new cdr();
        c.SwitchId = 1; c.InPartnerId = 5; c.IncomingRoute = "in"; c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.StartTime = LocalDateTime.of(2026, 6, 19, 14, 30, 0); c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60); c.RoundedDuration = BigDecimal.valueOf(60); c.Duration1 = BigDecimal.valueOf(60);
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

    private static LocalDateTime HourOf(LocalDateTime t) {
        return LocalDateTime.of(t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), 0, 0);
    }

    // LINQ .Single(): assert exactly one element and return it.
    private static <T> T single(List<T> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    @Test
    void Fresh_call_inserts_day_and_hour_rows() {
        var store = new InMemorySummaryStore();
        var ctx = new CdrSummaryContext(store, new CountingAutoIncrementManager(1000));
        var cdr = Sg10Cdr();

        ctx.PopulatePrevSummary(List.of(10),
                List.of(cdr.StartTime.toLocalDate().atStartOfDay()),
                List.of(HourOf(cdr.StartTime)));
        ctx.AddCall(cdr, Sg10Charge());
        ctx.WriteAllChanges();

        assertEquals(2, store.ExecutedSql.size());
        assertTrue(store.ExecutedSql.stream().anyMatch(s -> s.startsWith("insert into sum_voice_day_03")));
        assertTrue(store.ExecutedSql.stream().anyMatch(s -> s.startsWith("insert into sum_voice_hr_03")));
    }

    @Test
    void Existing_day_row_is_merged_onto_and_updated() {
        var cdr = Sg10Cdr();
        var charge = Sg10Charge();

        // the existing persisted day row = what this call builds, but already in the DB (id 100, one call)
        var existingDay = CdrSummaryBuilder.Build(cdr, charge, SummaryBucket.Day);
        existingDay.id = 100;
        var store = new InMemorySummaryStore();
        store.Seed(CdrSummaryType.sum_voice_day_03, existingDay);

        var ctx = new CdrSummaryContext(store, new CountingAutoIncrementManager(1000));
        ctx.PopulatePrevSummary(List.of(10),
                List.of(cdr.StartTime.toLocalDate().atStartOfDay()),
                List.of(HourOf(cdr.StartTime)));
        ctx.AddCall(cdr, charge);
        ctx.WriteAllChanges();

        // day -> UPDATE the existing row 100; hr -> INSERT (no prev)
        assertTrue(store.ExecutedSql.stream().anyMatch(
                s -> s.startsWith("update sum_voice_day_03") && s.contains("where id=100")));
        assertTrue(store.ExecutedSql.stream().anyMatch(s -> s.startsWith("insert into sum_voice_hr_03")));

        var dayRow = single(ctx.TableWiseSummaryCache().get(CdrSummaryType.sum_voice_day_03).GetItems());
        assertEquals(2L, dayRow.totalcalls);                                 // 1 existing + 1 this call
        assertEquals(0, new BigDecimal("2.0").compareTo(dayRow.customercost));   // 1.0 + 1.0
    }
}
