// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

import java.math.BigDecimal;

/**
 * One rate row for the ADMISSION path (config-manager today's-rates, GetMaxRatePerMinute). The post-call
 * rating/mediation path uses the verbatim legacy {@code rateassign} instead; this stays a lean record for
 * the admission-side rate plan map.
 */
public record Rate(
        long Id,
        String Prefix,
        int IdRatePlan,
        BigDecimal RateAmount,
        String CountryCode,
        int Category,
        int Resolution,
        BigDecimal MinDurationSec,
        int SurchargeTime,
        BigDecimal SurchargeAmount,
        int Inactive
) {
}
