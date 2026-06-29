// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class AnsIdOrigGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.AnsIdOrig != null && c.AnsIdOrig > 0;
    }

    @Override
    public String ValidationMessage() {
        return "AnsIdOrig must be > 0";
    }
}
