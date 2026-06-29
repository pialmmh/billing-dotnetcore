// Ported VERBATIM from legacy LibraryExtensions/MathAndDS/FractionCeilingHelper.cs
// (LibraryExtensions.FractionCeilingHelper). The string-based fraction-ceiling algorithm is preserved
// EXACTLY: decimal -> java.math.BigDecimal; Math.Ceiling -> setScale(0, RoundingMode.CEILING);
// Math.Pow(10, n) -> BigDecimal.TEN.pow(n). C# `throw new Exception` -> RuntimeException (house style,
// keeps the ctor unchecked as C# exceptions are).
package com.telcobright.billing.mediation.rating.ratecaching;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FractionCeilingHelper {
    private final String Amount;
    private final int MaxAllowedFractionPrecissionByTelcobright = 8;
    private final int CeilingAtFractionalPosition;

    public FractionCeilingHelper(BigDecimal amount, int ceilingAtFractionalPosition) {
        // C# amount.ToString(CultureInfo.InvariantCulture) -> a plain decimal string ('.' separator, no
        // scientific notation). toPlainString() is the faithful BigDecimal equivalent (toString() can
        // emit 'E' notation that would break the '.'-split in GetPreciseDecimal below).
        this.Amount = amount.toPlainString();
        this.CeilingAtFractionalPosition = ceilingAtFractionalPosition;
        if (this.CeilingAtFractionalPosition >= this.MaxAllowedFractionPrecissionByTelcobright) {
            throw new RuntimeException("Ceiling position cannot be >= MaxAllowedFractionPrecissionByTelcobright");
        }
    }

    public BigDecimal GetPreciseDecimal() {
        if (this.Amount.contains(".") == false) return new BigDecimal(this.Amount);
        String[] tempArr = this.Amount.split("\\.", -1);
        String nonFracPart = tempArr[0];
        String fracPart = GetFixedLengthFracPart(tempArr[1]);
        String fracpartUpToCeilingPosition = fracPart.substring(0, this.CeilingAtFractionalPosition + 1);

        String fullNumber = new StringBuilder(nonFracPart).append(".").append(fracpartUpToCeilingPosition)
                .toString();
        BigDecimal multiplier = BigDecimal.TEN.pow(this.CeilingAtFractionalPosition);
        return (new BigDecimal(fullNumber).multiply(multiplier).setScale(0, RoundingMode.CEILING).divide(multiplier));
    }

    private String GetFixedLengthFracPart(String fracPart) {

        if (fracPart.length() == this.MaxAllowedFractionPrecissionByTelcobright) {
            //fracPart = fracPart;
        } else if (fracPart.length() < this.MaxAllowedFractionPrecissionByTelcobright) {
            int zeroesToPad = this.MaxAllowedFractionPrecissionByTelcobright - fracPart.length();
            fracPart = padRight(fracPart, 8, '0');
        } else if (fracPart.length() > this.MaxAllowedFractionPrecissionByTelcobright) {
            fracPart = fracPart.substring(0, this.MaxAllowedFractionPrecissionByTelcobright);
        }
        return fracPart;
    }

    // C# String.PadRight(totalWidth, paddingChar): right-pad with paddingChar up to totalWidth (no-op if
    // already >= totalWidth). Java's String has no PadRight, so this reproduces it. The legacy call is
    // hardcoded PadRight(8, '0') (the computed zeroesToPad is unused — quirk preserved above).
    private static String padRight(String s, int totalWidth, char paddingChar) {
        if (s.length() >= totalWidth) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < totalWidth) {
            sb.append(paddingChar);
        }
        return sb.toString();
    }
}
