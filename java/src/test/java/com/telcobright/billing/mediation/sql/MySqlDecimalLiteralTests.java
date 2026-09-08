package com.telcobright.billing.mediation.sql;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The DECIMAL literal form emitted into the raw SQL the cdr/summary writers build by concatenation.
 *
 * <p>{@link BigDecimal#toString()} switches to SCIENTIFIC NOTATION once the adjusted exponent drops below -6.
 * Every money field passes through {@code ChargeableBuilder.Round(v, 8)} = {@code setScale(8)}, which puts zero
 * and sub-microunit values squarely in that range — so the statements carried {@code 0E-8} / {@code 1E-8} where
 * a DECIMAL column expects {@code 0.00000000} / {@code 0.00000001}. MySQL reads {@code 0E-8} as an APPROXIMATE
 * (double) literal, routing a money column through floating point. {@code toPlainString()} fixes the textual
 * form only; the numeric value is untouched, which {@link #Plain_form_is_numerically_identical} pins.
 */
class MySqlDecimalLiteralTests {

    /** The regression: a scale-8 ZERO — what an amount becomes after Round(0, 8) — must not be "0E-8". */
    @Test
    void Scale_eight_zero_renders_in_plain_form() {
        assertEquals("0.00000000", MySqlFieldExtensions.ToMySqlField(BigDecimal.ZERO.setScale(8)));
    }

    /** The smallest representable 8dp amount must not be "1E-8". */
    @Test
    void Smallest_eight_dp_value_renders_in_plain_form() {
        assertEquals("0.00000001", MySqlFieldExtensions.ToMySqlField(new BigDecimal("0.00000001")));
        assertEquals("0.00000001",
                MySqlFieldExtensions.ToMySqlField(new BigDecimal("0.000000009").setScale(8, RoundingMode.HALF_EVEN)));
    }

    /** An ordinary rated amount keeps its exact text, trailing zeros and all. */
    @Test
    void Ordinary_money_values_are_unchanged() {
        assertEquals("0.77500000", MySqlFieldExtensions.ToMySqlField(new BigDecimal("0.77500000")));
        assertEquals("1.82500000", MySqlFieldExtensions.ToMySqlField(new BigDecimal("1.82500000")));
        assertEquals("0.50", MySqlFieldExtensions.ToMySqlField(new BigDecimal("0.50")));
    }

    /** Negative values (credits/adjustments) must stay plain, sign included. */
    @Test
    void Negative_values_render_in_plain_form() {
        assertEquals("-0.00000001", MySqlFieldExtensions.ToMySqlField(new BigDecimal("-0.00000001")));
        assertEquals("-1.82500000", MySqlFieldExtensions.ToMySqlField(new BigDecimal("-1.82500000")));
        assertEquals("-93.643", MySqlFieldExtensions.ToMySqlField(new BigDecimal("-93.643")));
    }

    /**
     * BigDecimal has no negative zero: a tiny NEGATIVE value that rounds away at 8dp comes back as plain
     * {@code 0.00000000}, not {@code -0.00000000}. Pinned because the sign silently disappearing is the sort of
     * thing a reader would otherwise suspect the literal formatter of doing.
     */
    @Test
    void Negative_value_rounding_to_zero_loses_its_sign() {
        BigDecimal rounded = new BigDecimal("-0.000000001").setScale(8, RoundingMode.HALF_EVEN);
        assertEquals(0, BigDecimal.ZERO.compareTo(rounded));
        assertEquals("0.00000000", MySqlFieldExtensions.ToMySqlField(rounded));
    }

    /** Whole numbers and the duration shapes the summary carries keep their existing rendering. */
    @Test
    void Integer_and_duration_shapes_are_unchanged() {
        assertEquals("0", MySqlFieldExtensions.ToMySqlField(BigDecimal.ZERO));          // scale 0
        assertEquals("93", MySqlFieldExtensions.ToMySqlField(BigDecimal.valueOf(93)));  // GetA2ZDuration output
        assertEquals("93.643", MySqlFieldExtensions.ToMySqlField(new BigDecimal("93.643")));
        assertEquals("60.00", MySqlFieldExtensions.ToMySqlField(new BigDecimal("60.00")));
    }

    /** A null decimal is still the unquoted SQL keyword, not the string "null". */
    @Test
    void Null_still_renders_as_sql_null() {
        assertEquals("null", MySqlFieldExtensions.ToMySqlField((BigDecimal) null));
    }

    /**
     * The property that matters across the board: whatever the value, the emitted literal parses back to the
     * SAME number and never carries an exponent marker.
     */
    @Test
    void Plain_form_is_numerically_identical() {
        String[] values = {"0", "0.00000001", "-0.00000001", "0.77500000", "1.82500000", "-1.82500000",
                "93.643", "219.17", "123.55000000", "1000000.00000000"};
        for (String v : values) {
            for (BigDecimal d : new BigDecimal[]{new BigDecimal(v), new BigDecimal(v).setScale(8, RoundingMode.HALF_EVEN)}) {
                String literal = MySqlFieldExtensions.ToMySqlField(d);
                assertFalse(literal.contains("E") || literal.contains("e"),
                        "literal must not use scientific notation: " + d + " -> " + literal);
                assertEquals(0, d.compareTo(new BigDecimal(literal)),
                        "literal must parse back to the same number: " + d + " -> " + literal);
            }
        }
    }
}
