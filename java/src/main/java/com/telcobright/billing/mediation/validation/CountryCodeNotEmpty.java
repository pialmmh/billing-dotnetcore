// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class CountryCodeNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.CountryCode == null || c.CountryCode.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "CountryCode must not be empty";
    }
}
