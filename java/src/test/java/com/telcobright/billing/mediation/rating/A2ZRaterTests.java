package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faithful A2Z math (ported from ExecuteA2ZRating + PrefixMatcher.GetA2ZDuration): pulse, the
 * ms-threshold, and the SURCHARGE = minimum-initial-period model (NOT a flat fee).
 */
class A2ZRaterTests {

    // The C# helper `(decimal, decimal) T(A2ZRateResult r) => (r.BilledDurationSec, r.Amount)` compared
    // value-tuples; C# decimal ValueTuple equality is SCALE-INSENSITIVE. BigDecimal.equals is not, so we
    // compare each component by VALUE (compareTo).
    private static void T(BigDecimal expBilledDurationSec, BigDecimal expAmount, A2ZRateResult r) {
        assertEquals(0, expBilledDurationSec.compareTo(r.BilledDurationSec()));
        assertEquals(0, expAmount.compareTo(r.Amount()));
    }

    @Test
    void Per_minute_no_pulse() {
        var rate = TestData.Ra(1, "1.0").build();   // Resolution 0, SurchargeTime 0
        T(new BigDecimal("60"), new BigDecimal("1.0"), A2ZRater.Rate(rate, new BigDecimal("60")));
        T(new BigDecimal("30"), new BigDecimal("0.5"), A2ZRater.Rate(rate, new BigDecimal("30")));
        // ceil to 13; amount = decimal.Round(13/60, 8)
        T(new BigDecimal("13"), new BigDecimal("13").divide(new BigDecimal("60"), 8, RoundingMode.HALF_EVEN),
                A2ZRater.Rate(rate, new BigDecimal("12.07")));
    }

    @Test
    void Per_minute_pulse_rounds_up() {
        var rate = TestData.Ra(1, "1.0").resolution(60).build();
        T(new BigDecimal("60"), new BigDecimal("1.0"), A2ZRater.Rate(rate, new BigDecimal("60")));
        T(new BigDecimal("120"), new BigDecimal("2.0"), A2ZRater.Rate(rate, new BigDecimal("61")));   // 61 -> next minute
    }

    @Test
    void Surcharge_is_a_minimum_initial_period_not_a_flat_fee() {
        var rate = TestData.Ra(1, "1.0").resolution(6).surchargeTime(30).build();
        // 12.07s <= 30 => the whole minimum initial period bills: 30s, amount 30/60 = 0.5
        T(new BigDecimal("30"), new BigDecimal("0.5"), A2ZRater.Rate(rate, new BigDecimal("12.07")));
        // 42s > 30 => 30 (initial) + pulse6(12)=12 => 42; amount 42/60 = 0.7
        T(new BigDecimal("42"), new BigDecimal("0.7"), A2ZRater.Rate(rate, new BigDecimal("42")));
    }

    @Test
    void Min_duration_ms_threshold() {
        var rate = TestData.Ra(1, "1.0").minDurationSec(0.1f).build();
        // frac .05 < .1 -> floor
        assertEquals(0, new BigDecimal("60").compareTo(A2ZRater.A2ZDuration(new BigDecimal("60.05"), rate)));
        // frac .15 >= .1 -> ceil
        assertEquals(0, new BigDecimal("61").compareTo(A2ZRater.A2ZDuration(new BigDecimal("60.15"), rate)));
    }
}
