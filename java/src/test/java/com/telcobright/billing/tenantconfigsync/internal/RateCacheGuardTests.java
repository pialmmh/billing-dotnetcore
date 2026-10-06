package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.IRateLoader;
import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.mediation.rating.ratecaching.TupleRateLoader;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.spi.ConfigManagerUnavailableException;
import com.telcobright.billing.testsupport.FakeConfigManagerServer;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The every-minute RateCacheGuard when TOMORROW's rates cannot be fetched — the 2026-10-07 production state, where
 * this config-manager answers {@code /get-rates-by-date} with HTTP 500. Retrying every minute forever was pure
 * pressure (and, with the client's connection leak, one stranded socket per minute): the retries must back off and
 * stay bounded, today must keep being warmed, and the real error must still reach the rating path.
 */
class RateCacheGuardTests {
    private static final ZoneId Zone = ZoneId.systemDefault();

    /** A clock the test moves by hand — starts today at 12:00 so an hour of ticks never crosses midnight. */
    static final class ManualClock extends Clock {
        private Instant now = LocalDate.now(Zone).atTime(12, 0).atZone(Zone).toInstant();

        void Advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return Zone; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
    }

    /** Today loads; tomorrow (relative to the clock) fails with the config-manager 500 until made available. */
    static final class TomorrowFailingLoader implements IRateLoader {
        final Clock clock;
        final Map<LocalDate, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Map<LocalDate, AtomicInteger> failures = new ConcurrentHashMap<>();
        volatile boolean tomorrowAvailable;

        TomorrowFailingLoader(Clock clock) {
            this.clock = clock;
        }

        int Calls(LocalDate day) {
            AtomicInteger n = calls.get(day);
            return n == null ? 0 : n.get();
        }

        int Failures(LocalDate day) {
            AtomicInteger n = failures.get(day);
            return n == null ? 0 : n.get();
        }

        @Override
        public Map<TupleByPeriod, Map<String, List<Rateext>>> LoadDay(DateRange dRange) {
            LocalDate day = dRange.StartDate.toLocalDate();
            calls.computeIfAbsent(day, k -> new AtomicInteger()).incrementAndGet();
            if (day.equals(LocalDate.now(clock).plusDays(1)) && !tomorrowAvailable) {
                failures.computeIfAbsent(day, k -> new AtomicInteger()).incrementAndGet();
                throw new ConfigManagerUnavailableException("tenant_a",
                        "config-manager http://config-manager/get-rates-by-date?name=tenant_a&date=" + day + " returned 500");
            }
            return new HashMap<>();
        }
    }

    static Tenant TenantWith(String db, RateCache cache) {
        Tenant t = new Tenant();
        t.Name = db;
        t.DbName = db;
        t.Context = new DynamicContext();
        t.Context.MediationContext = new MediationContext();
        t.Context.MediationContext.RateCache = cache;
        return t;
    }

    static ITenantRegistry RegistryOf(Tenant root) {
        return new ITenantRegistry() {
            @Override public boolean IsLoaded() { return true; }
            @Override public Tenant FindByDbName(String dbName) { return root; }
            @Override public List<Tenant> AncestorChain(String dbName) { return List.of(root); }
            @Override public Collection<Tenant> Roots() { return List.of(root); }
        };
    }

    @Test
    void an_hour_of_failing_tomorrow_fetches_is_bounded_not_one_per_minute() {
        var clock = new ManualClock();
        var loader = new TomorrowFailingLoader(clock);
        var cache = new RateCache(loader, new HashMap<>(), 7);
        var guard = new RateCacheGuard(RegistryOf(TenantWith("tenant_a", cache)), clock);
        LocalDate today = LocalDate.now(clock);

        for (int minute = 0; minute < 60; minute++) {
            guard.Tick();
            clock.Advance(Duration.ofMinutes(1));
        }

        assertEquals(6, loader.Calls(today.plusDays(1)), "attempts at minutes 0, 1, 3, 7, 15, 31 — not 60");
        assertEquals(1, loader.Calls(today), "today loads once and stays cached");
        assertTrue(cache.DateRangeWiseRateDic.containsKey(RateCache.DayRange(today)), "today is warm");
    }

    @Test
    void the_retry_succeeds_as_soon_as_tomorrow_is_served_and_then_stops_fetching() {
        var clock = new ManualClock();
        var loader = new TomorrowFailingLoader(clock);
        var cache = new RateCache(loader, new HashMap<>(), 7);
        var guard = new RateCacheGuard(RegistryOf(TenantWith("tenant_a", cache)), clock);
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);

        for (int minute = 0; minute < 30; minute++) {
            if (minute == 10) loader.tomorrowAvailable = true;   // config-manager fixed at minute 10
            guard.Tick();
            clock.Advance(Duration.ofMinutes(1));
        }

        // failed at 0, 1, 3, 7; the next scheduled retry (minute 15) succeeds; cached from then on
        assertEquals(5, loader.Calls(tomorrow));
        assertTrue(cache.DateRangeWiseRateDic.containsKey(RateCache.DayRange(tomorrow)), "tomorrow is warm");
    }

    @Test
    void the_rating_path_still_gets_the_real_error_while_the_guard_backs_off() {
        var clock = new ManualClock();
        var loader = new TomorrowFailingLoader(clock);
        var cache = new RateCache(loader, new HashMap<>(), 7);
        var guard = new RateCacheGuard(RegistryOf(TenantWith("tenant_a", cache)), clock);
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);

        guard.Tick();   // first failure: the guard now waits before its next attempt
        clock.Advance(Duration.ofSeconds(30));

        // a CDR rated for tomorrow does its own fetch and fails with the real cause (-> cdrerror), not silently
        var ex = assertThrows(ConfigManagerUnavailableException.class,
                () -> cache.GetRateDictsByDay(RateCache.DayRange(tomorrow)));
        assertTrue(ex.getMessage().contains("returned 500"), ex.getMessage());
        assertEquals(2, loader.Calls(tomorrow), "one guard attempt + the rating path's own attempt");
    }

    @Test
    void retries_stay_bounded_over_days_and_config_reloads() {
        var clock = new ManualClock();
        var loader = new TomorrowFailingLoader(clock);
        Tenant tenant = TenantWith("tenant_a", new RateCache(loader, new HashMap<>(), 7));
        var guard = new RateCacheGuard(RegistryOf(tenant), clock);
        LocalDate day1 = LocalDate.now(clock);

        // two days of minute ticks; a config reload (fresh RateCache) every 7 minutes, as a reload swaps the context
        for (int minute = 0; minute < 2 * 24 * 60; minute++) {
            if (minute % 7 == 0) tenant.Context.MediationContext.RateCache = new RateCache(loader, new HashMap<>(), 7);
            guard.Tick();
            clock.Advance(Duration.ofMinutes(1));
        }

        // each day's "tomorrow" fails all day: about one attempt per 30 min once backed off (~52 over a whole day,
        // 28 over day 1's remaining 12 h) instead of one per minute (1,440) — and a reload does not reset the backoff
        int day2Failures = loader.Failures(day1.plusDays(1));
        int day3Failures = loader.Failures(day1.plusDays(2));
        assertEquals(28, day2Failures, "tomorrow failed from 12:00 to midnight of day 1");
        assertEquals(52, day3Failures, "tomorrow failed all of day 2");
        // and once that day became TODAY (midnight), it loaded straight away — today is never held back
        assertTrue(loader.Calls(day1.plusDays(1)) > day2Failures, "day 2 loaded as today after midnight");
        assertEquals(1, guard.FailingDays(), "only the current tomorrow is tracked; past days were pruned");
    }

    @Test
    void each_failed_attempt_logs_one_warning_and_only_the_first_carries_the_stack_trace() {
        var clock = new ManualClock();
        var loader = new TomorrowFailingLoader(clock);
        var guard = new RateCacheGuard(RegistryOf(TenantWith("tenant_a", new RateCache(loader, new HashMap<>(), 7))), clock);
        var records = new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var jul = java.util.logging.Logger.getLogger(RateCacheGuard.class.getName());
        jul.addHandler(handler);
        try {
            for (int minute = 0; minute < 70; minute++) {
                if (minute == 40) loader.tomorrowAvailable = true;   // served again; the retry due at 61 picks it up
                guard.Tick();
                clock.Advance(Duration.ofMinutes(1));
            }
        } finally {
            jul.removeHandler(handler);
        }

        var warnings = records.stream()
                .filter(r -> r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()).toList();
        assertEquals(6, warnings.size(), "one WARN per failed attempt (0, 1, 3, 7, 15, 31), not one per minute");
        assertEquals(1, warnings.stream().filter(r -> r.getThrown() != null).count(), "the stack trace once per run");
        assertTrue(Text(warnings.get(0)).contains("next attempt in 60s"), Text(warnings.get(0)));
        assertTrue(Text(warnings.get(5)).contains("6 attempts in a row") && Text(warnings.get(5)).contains("returned 500"),
                Text(warnings.get(5)));
        var recoveries = records.stream().filter(r -> Text(r).contains("loaded after")).toList();
        assertEquals(1, recoveries.size(), "the recovery is logged once");
        assertTrue(Text(recoveries.get(0)).contains("after 6 failed attempt(s)"), Text(recoveries.get(0)));
    }

    /** The rendered log line, whichever way the backend hands the record over (printf format + args, or done). */
    private static String Text(java.util.logging.LogRecord r) {
        Object[] args = r.getParameters();
        return args == null || args.length == 0 ? String.valueOf(r.getMessage()) : String.format(r.getMessage(), args);
    }

    @Test
    void with_the_real_client_against_a_500_config_manager_requests_and_connections_stay_bounded() throws Exception {
        try (var configManager = new FakeConfigManagerServer()) {
            configManager.Respond(500, List.of("{\"status\":500,", "\"error\":\"Internal Server Error\",",
                    "\"path\":\"/get-rates-by-date\"}"));
            HttpClient http = HttpClient.newHttpClient();
            try {
                var opts = new TenantConfigSyncOptions();
                opts.ConfigManager.BaseUrl = configManager.BaseUrl();
                opts.ConfigManager.TimeoutSeconds = 5;
                var client = new HttpConfigManagerClient(http, opts);

                var clock = new ManualClock();
                LocalDate today = LocalDate.now(clock);
                // the production chain: RateCache -> TupleRateLoader -> snapshot (today only) -> client for any other day
                var rows = new SnapshotBackfillRateRows("tenant_a", Map.of(today, Map.of()), Map.of(), client);
                var cache = new RateCache(new TupleRateLoader(List.of(), rows, new HashMap<>()), new HashMap<>(), 7);
                var guard = new RateCacheGuard(RegistryOf(TenantWith("tenant_a", cache)), clock);

                for (int minute = 0; minute < 60; minute++) {
                    guard.Tick();
                    clock.Advance(Duration.ofMinutes(1));
                }

                assertEquals(6, configManager.Requests("/get-rates-by-date"), "6 attempts in an hour, not 60");
                int open = configManager.OpenAfterSettling(1, Duration.ofSeconds(3));
                assertTrue(open <= 1, () -> "connections still held by billing-core: " + open);
            } finally {
                http.shutdownNow();
            }
        }
    }
}
