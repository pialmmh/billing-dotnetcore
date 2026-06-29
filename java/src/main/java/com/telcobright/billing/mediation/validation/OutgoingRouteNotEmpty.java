// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import com.telcobright.billing.mediation.engine.models.cdr;

public final class OutgoingRouteNotEmpty implements IValidationRule<cdr> {
    @Override
    public boolean Validate(cdr c) {
        return !(c.OutgoingRoute == null || c.OutgoingRoute.isEmpty());
    }

    @Override
    public String ValidationMessage() {
        return "OutgoingRoute must not be empty";
    }
}
