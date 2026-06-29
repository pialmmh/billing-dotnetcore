// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class MatchedPrefixCustomerNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.MatchedPrefixCustomer == null || c.MatchedPrefixCustomer.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "MatchedPrefixCustomer must not be empty";
    }
}
