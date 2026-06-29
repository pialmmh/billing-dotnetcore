package com.telcobright.billing.mediation.rating;

import java.math.BigDecimal;

/**
 * The A2Z charge for one leg over a matched {@code rateassign}: pulse/surcharge-adjusted
 * billed duration + the amount.
 */
public record A2ZRateResult(BigDecimal BilledDurationSec, BigDecimal Amount) {
}
