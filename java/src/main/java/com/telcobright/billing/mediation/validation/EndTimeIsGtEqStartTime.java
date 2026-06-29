// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class EndTimeIsGtEqStartTime implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !c.EndTime.isBefore(c.StartTime);
    }

    @Override
    public String ValidationMessage() {
        return "EndTime must be >= StartTime";
    }
}
