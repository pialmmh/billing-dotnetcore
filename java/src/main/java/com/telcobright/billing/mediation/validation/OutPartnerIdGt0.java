// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class OutPartnerIdGt0 implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return c.OutPartnerId != null && c.OutPartnerId > 0;
    }

    @Override
    public String ValidationMessage() {
        return "OutPartnerId must be > 0";
    }
}
