// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class UniqueBillIdNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.UniqueBillId == null || c.UniqueBillId.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "UniqueBillId must not be empty";
    }
}
