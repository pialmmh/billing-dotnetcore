package com.telcobright.billing.mediation.validation;

import java.util.List;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * Faithful port of legacy {@code MediationValidator.ExecuteRules}: AFTER mediation, a cdr is qualified
 * against the validation checklists before it can reach the summary + cdr table. Order:
 * <ol>
 * <li>the COMMON checklist (all cdrs);</li>
 * <li>{@code ServiceGroup} must be &gt; 0;</li>
 * <li>the detected SG's checklist — the ANSWERED one for chargeable calls (ChargingStatus == 1), else the
 *   UNANSWERED one — so failed and successful calls have separate checklists.</li>
 * </ol>
 * Returns {@code ""} when the cdr qualifies, otherwise the first failing rule's message.
 */
public final class MediationValidator {
    private MediationValidator() {}

    public static String Validate(cdr cdr, MediationContext mediation) {
        for (IValidationRule<cdr> rule : mediation.CommonChecklist)                 // common checklist (all cdrs)
            if (!rule.Validate(cdr)) return rule.ValidationMessage();

        if (cdr.ServiceGroup <= 0) return "ServiceGroup must be > 0";

        ServiceGroupConfiguration sg = mediation.ServiceGroupConfigurations.get(cdr.ServiceGroup);
        if (sg != null) {
            // chargeable (answered) calls use the answered checklist; failed calls the unanswered one.
            List<IValidationRule<cdr>> checklist =
                    ((cdr.ChargingStatus != null ? cdr.ChargingStatus : 0) == 1)
                            ? sg.AnsweredChecklist() : sg.UnansweredChecklist();
            for (IValidationRule<cdr> rule : checklist)
                if (!rule.Validate(cdr)) return rule.ValidationMessage();
        }
        return "";
    }
}
