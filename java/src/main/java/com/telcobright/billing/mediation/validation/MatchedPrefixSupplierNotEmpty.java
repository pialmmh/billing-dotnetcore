// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class MatchedPrefixSupplierNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.MatchedPrefixSupplier == null || c.MatchedPrefixSupplier.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "MatchedPrefixSupplier must not be empty";
    }
}
