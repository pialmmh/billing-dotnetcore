// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class InPartnerIdGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.InPartnerId != null && c.InPartnerId > 0;
    }

    @Override
    public String ValidationMessage() {
        return "InPartnerId must be > 0";
    }
}
