package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RateCache's rate-row source: today/tomorrow come from the pushed snapshot (no network); any other day
 *  is fetched from config-manager via GetRatesForDate. */
class SnapshotBackfillRateRowsTests {

    static final class RecordingClient implements IConfigManagerClient {
        String lastDb;
        LocalDate lastDate;
        int calls;

        @Override public Tenant GetTenantRoot(String tenantName) { throw new UnsupportedOperationException(); }

        @Override public Map<Integer, Map<String, Rate>> GetRatesForDate(String tenantDbName, LocalDate date) {
            this.lastDb = tenantDbName;
            this.lastDate = date;
            this.calls++;
            return Map.of();   // empty served rates -> empty engine rows
        }
    }

    @Test
    void prewarmed_today_is_served_from_snapshot_without_a_fetch() {
        LocalDate today = LocalDate.now();
        Map<Integer, List<rate>> todayRows = Map.of(1, List.of(new rate()));
        RecordingClient client = new RecordingClient();

        SnapshotBackfillRateRows provider = new SnapshotBackfillRateRows(
                "res_1", Map.of(today, todayRows), todayRows, client);

        assertSame(todayRows, provider.rowsForDate(today), "today served from the snapshot");
        assertEquals(0, client.calls, "no network fetch for a pre-warmed day");
    }

    @Test
    void a_non_prewarmed_day_is_fetched_from_config_manager() {
        LocalDate today = LocalDate.now();
        LocalDate old = LocalDate.of(2020, 5, 20);
        RecordingClient client = new RecordingClient();

        SnapshotBackfillRateRows provider = new SnapshotBackfillRateRows(
                "res_7", Map.of(today, Map.of()), Map.of(), client);

        Map<Integer, List<rate>> rows = provider.rowsForDate(old);

        assertEquals(1, client.calls, "an old day triggers exactly one config-manager fetch");
        assertEquals("res_7", client.lastDb);
        assertEquals(old, client.lastDate);
        assertTrue(rows.isEmpty(), "empty served rates map to empty engine rows");
    }

    @Test
    void no_client_falls_back_to_today_rows() {
        LocalDate today = LocalDate.now();
        Map<Integer, List<rate>> todayRows = Map.of(9, List.of(new rate()));
        SnapshotBackfillRateRows provider = new SnapshotBackfillRateRows(
                "res_1", Map.of(today, todayRows), todayRows, null);

        assertSame(todayRows, provider.rowsForDate(LocalDate.of(2019, 1, 1)),
                "without a client a back-date degrades to today's rows (legacy behaviour), not empty");
    }
}
