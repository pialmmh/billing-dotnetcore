// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class IncomingRouteNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.IncomingRoute == null || c.IncomingRoute.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "IncomingRoute must not be empty";
    }
}
