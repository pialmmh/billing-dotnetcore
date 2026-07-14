package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.rating.ratecaching.RateRowsByDateProvider;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The rate-row source for one tenant's RateCache: today + tomorrow come from the pushed snapshot (pre-warmed,
 * no network); any OTHER day is fetched live from config-manager ({@code /get-rates-by-date}) and mapped to
 * engine rate rows — this is what lets the engine back-process old CDRs. When no client is wired (unit tests),
 * a non-pre-warmed day yields empty rows rather than a fetch.
 */
final class SnapshotBackfillRateRows implements RateRowsByDateProvider {
    private final String tenantDbName;
    private final Map<LocalDate, Map<Integer, List<rate>>> prewarmed;
    private final Map<Integer, List<rate>> fallbackRows;   // today's rows — used when NO client is wired
    private final IConfigManagerClient client;

    SnapshotBackfillRateRows(String tenantDbName, Map<LocalDate, Map<Integer, List<rate>>> prewarmed,
            Map<Integer, List<rate>> fallbackRows, IConfigManagerClient client) {
        this.tenantDbName = tenantDbName;
        this.prewarmed = prewarmed;
        this.fallbackRows = fallbackRows != null ? fallbackRows : Map.of();
        this.client = client;
    }

    @Override
    public Map<Integer, List<rate>> rowsForDate(LocalDate date) {
        Map<Integer, List<rate>> rows = prewarmed.get(date);
        if (rows != null) return rows;                 // today/tomorrow — snapshot, no network
        // No client wired (unit tests, or a deployment without the by-date endpoint): degrade to the legacy
        // behaviour — the today snapshot serves every day — rather than mis-rating a back-date as empty.
        if (client == null) return fallbackRows;
        Map<Integer, Map<String, Rate>> served = client.GetRatesForDate(tenantDbName, date);
        return ConfigManagerMapper.ToRateRowsByRatePlan(served);
    }
}
