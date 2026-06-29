package com.telcobright.billing.mediation.servicegroups;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden checks for BD number normalization (the strip-+/0/00880/880880/880 logic shared by the SgDomOffnet*
 * detectors). Faithful port of BdNumberNormalizerTests.cs; the C# {@code [Theory]}+{@code [InlineData]}
 * become {@code @ParameterizedTest}+{@code @CsvSource}.
 */
class BdNumberNormalizerTests {

    @ParameterizedTest
    @CsvSource({
            "01712345678, 1712345678",       // leading trunk 0
            "8801712345678, 1712345678",     // 880 country code
            "008801712345678, 1712345678",   // 00880 IDD form
            "880880171234, 171234",          // 880880 teletalk quirk
            "+8801712345678, 1712345678",    // leading + then 880
            "1712345678, 1712345678",        // already national-significant
    })
    void Normalizes(String raw, String expected) {
        assertEquals(expected, BdNumberNormalizer.Normalize(raw));
    }

    @Test
    void Blank_passes_through_safely() {
        assertEquals("", BdNumberNormalizer.Normalize(null));
        assertEquals("", BdNumberNormalizer.Normalize(""));
        assertEquals("   ", BdNumberNormalizer.Normalize("   "));   // whitespace left untouched
    }
}
