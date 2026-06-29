// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class AnsIdTermGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.AnsIdTerm != null && c.AnsIdTerm > 0;
    }

    @Override
    public String ValidationMessage() {
        return "AnsIdTerm must be > 0";
    }
}
