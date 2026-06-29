// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import java.math.BigDecimal;
import java.util.function.Function;

import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * The legacy "&lt;field&gt;Gt0 {Data = minDurationSec}" family: when {@code DurationSec >= Data} the field
 * must be &gt; 0, otherwise it must be 0 (no charge/metric for short/zero-duration calls). One class
 * parameterised by field; the registry names each variant (Duration2Gt0, InPartnerCostGt0, CostIcxInGt0, …).
 */
public final class DurationGatedPositive implements IValidationRule<cdr> {
    private final String _field;
    private final Function<cdr, BigDecimal> _value;
    private final BigDecimal _minDurationSec;

    public DurationGatedPositive(String field, Function<cdr, BigDecimal> value, BigDecimal minDurationSec) {
        _field = field;
        _value = value;
        _minDurationSec = minDurationSec;
    }

    @Override
    public boolean Validate(cdr c) {
        BigDecimal raw = _value.apply(c);
        BigDecimal v = raw != null ? raw : BigDecimal.ZERO;
        return c.DurationSec.compareTo(_minDurationSec) >= 0
                ? v.compareTo(BigDecimal.ZERO) > 0
                : v.compareTo(BigDecimal.ZERO) == 0;
    }

    @Override
    public String ValidationMessage() {
        return _field + " must be > 0 when DurationSec >= " + _minDurationSec + " (else 0)";
    }
}
