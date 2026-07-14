// Supplies the rate rows (idRatePlan -> rows) valid on a given DATE, so the RateCache can build ANY day:
// today/tomorrow come from the pushed snapshot, older days are fetched on demand from config-manager
// (/get-rates-by-date). The legacy cache built every day from one today-only in-memory snapshot; this seam
// lets a back-dated CDR load its own day's rates instead of silently getting today's (or nothing).
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface RateRowsByDateProvider {
    /** The rate rows per rate-plan valid on {@code date}. Never null (empty map when the day has no rates). */
    Map<Integer, List<rate>> rowsForDate(LocalDate date);

    /**
     * Backward-compatible provider that returns the SAME fixed rows for every date — the legacy today-only
     * snapshot behaviour. Used by tests and the empty default context, where no config-manager back-fill exists.
     */
    static RateRowsByDateProvider ofFixed(Map<Integer, List<rate>> fixedRows) {
        Map<Integer, List<rate>> rows = fixedRows != null ? fixedRows : Map.of();
        return date -> rows;
    }
}
