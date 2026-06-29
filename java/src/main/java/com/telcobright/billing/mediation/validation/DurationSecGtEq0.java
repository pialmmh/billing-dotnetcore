// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import java.math.BigDecimal;

import com.telcobright.billing.mediation.engine.models.cdr;

/** The answered/billable seconds must be non-negative. */
public final class DurationSecGtEq0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.DurationSec.compareTo(BigDecimal.ZERO) >= 0;
    }

    @Override
    public String ValidationMessage() {
        return "DurationSec must be >= 0";
    }
}
