// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import java.math.BigDecimal;

import com.telcobright.billing.mediation.engine.models.cdr;

/** An answered (DurationSec &gt; 0) call must have ChargingStatus 1; otherwise 0 or 1 is allowed. */
public final class ChargingStatus1WhenDurationGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.DurationSec.compareTo(BigDecimal.ZERO) > 0
                ? (c.ChargingStatus != null && c.ChargingStatus == 1)
                : (c.ChargingStatus != null && (c.ChargingStatus == 0 || c.ChargingStatus == 1));
    }

    @Override
    public String ValidationMessage() {
        return "ChargingStatus must be 1 when DurationSec > 0";
    }
}
