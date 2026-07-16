package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.rating.ratecaching.RateRowsByDateProvider;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the billing-core rate-row provider does per DATE (today / tomorrow / previous), which is the source the
 * RateCache loads each day from. Confirms: today (and tomorrow, IF served) come from the pushed snapshot with
 * NO network; any other day is fetched from config-manager /get-rates-by-date (live since 2026-07-16) — and if
 * that fetch FAILS the day fails, unless a client is absent (then it degrades to today's rows).
 */
class RateCacheDateLoadCheck {

    private final LocalDate today = LocalDate.now();
    private final Map<Integer, List<rate>> todayRows = Map.of(1, List.of(new rate()));

    // a config-manager client whose /get-rates-by-date fails (e.g. a 500 from a build without the endpoint).
    private static final IConfigManagerClient FIVE_HUNDRED = new IConfigManagerClient() {
        @Override public Tenant GetTenantRoot(String tenantName) { return null; }
        @Override public Map<Integer, Map<String, Rate>> GetRatesForDate(String tenantDbName, LocalDate date) {
            throw new RuntimeException("config-manager /get-rates-by-date returned 500 (No static resource)");
        }
    };

    @Test
    void today_loads_from_snapshot__previous_and_next_day_hit_the_500() {
        // snapshot pre-warms ONLY today (config-manager does not serve ratePlanWiseTomorrowsRates today).
        Map<LocalDate, Map<Integer, List<rate>>> prewarmed = Map.of(today, todayRows);
        RateRowsByDateProvider p = new SnapshotBackfillRateRows("telcobright", prewarmed, todayRows, FIVE_HUNDRED);

        // TODAY: from the snapshot, no network -> OK
        assertSame(todayRows, p.rowsForDate(today));

        // NEXT DAY (tomorrow): not pre-warmed (no tomorrow data) -> /get-rates-by-date -> 500 -> FAILS
        assertThrows(RuntimeException.class, () -> p.rowsForDate(today.plusDays(1)));

        // PREVIOUS days (within the 7-day window): -> /get-rates-by-date -> 500 -> FAILS
        assertThrows(RuntimeException.class, () -> p.rowsForDate(today.minusDays(1)));
        assertThrows(RuntimeException.class, () -> p.rowsForDate(today.minusDays(7)));
    }

    @Test
    void with_tomorrow_served_the_next_day_loads_too() {
        // if config-manager served ratePlanWiseTomorrowsRates, tomorrow would be pre-warmed (no network).
        Map<Integer, List<rate>> tomorrowRows = Map.of(1, List.of(new rate()));
        Map<LocalDate, Map<Integer, List<rate>>> prewarmed =
                Map.of(today, todayRows, today.plusDays(1), tomorrowRows);
        RateRowsByDateProvider p = new SnapshotBackfillRateRows("telcobright", prewarmed, todayRows, FIVE_HUNDRED);

        assertSame(todayRows, p.rowsForDate(today));
        assertSame(tomorrowRows, p.rowsForDate(today.plusDays(1)));   // next-day OK when served
    }

    @Test
    void no_client_degrades_to_todays_rows_for_every_day() {
        // the fallback path (no by-date endpoint wired): today's rows serve every day (legacy behaviour).
        Map<LocalDate, Map<Integer, List<rate>>> prewarmed = Map.of(today, todayRows);
        RateRowsByDateProvider p = new SnapshotBackfillRateRows("telcobright", prewarmed, todayRows, null);

        assertSame(todayRows, p.rowsForDate(today));
        assertSame(todayRows, p.rowsForDate(today.minusDays(3)));     // previous -> today's rows
        assertSame(todayRows, p.rowsForDate(today.plusDays(1)));      // next-day -> today's rows
        assertEquals(1, p.rowsForDate(today.minusDays(30)).size());
    }
}
