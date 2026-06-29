// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class OriginatingCalledNumberNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.OriginatingCalledNumber == null || c.OriginatingCalledNumber.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "OriginatingCalledNumber must not be empty";
    }
}
