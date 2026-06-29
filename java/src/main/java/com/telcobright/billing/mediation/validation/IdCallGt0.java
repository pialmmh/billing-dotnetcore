// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class IdCallGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.IdCall > 0;
    }

    @Override
    public String ValidationMessage() {
        return "IdCall must be > 0";
    }
}
