using Billing.Mediation.Context;
using MediationModel;

namespace Billing.Mediation.Validation;

/// <summary>
/// A mediation validation rule (legacy <c>IValidationRule&lt;cdr&gt;</c>) — one item in a qualification
/// checklist. <see cref="Validate"/> returns false to REJECT the cdr; <see cref="ValidationMessage"/> is the
/// rejection reason written to the cdr's <c>ErrorCode</c> and the cdr is routed to <c>cdrerror</c>. Kept
/// generic so the same contract validates other targets if needed (legacy was IValidationRule&lt;T&gt;).
/// </summary>
public interface IValidationRule<in T>
{
    bool Validate(T target);
    string ValidationMessage { get; }
}

/// <summary>
/// Faithful port of legacy <c>MediationValidator.ExecuteRules</c>: AFTER mediation, a cdr is qualified
/// against the validation checklists before it can reach the summary + cdr table. Order:
/// <list type="number">
/// <item>the COMMON checklist (all cdrs);</item>
/// <item><c>ServiceGroup</c> must be &gt; 0;</item>
/// <item>the detected SG's checklist — the ANSWERED one for chargeable calls (ChargingStatus == 1), else the
///   UNANSWERED one — so failed and successful calls have separate checklists.</item>
/// </list>
/// Returns <c>""</c> when the cdr qualifies, otherwise the first failing rule's message.
/// </summary>
public static class MediationValidator
{
    public static string Validate(cdr cdr, MediationContext mediation)
    {
        foreach (var rule in mediation.CommonChecklist)                 // common checklist (all cdrs)
            if (!rule.Validate(cdr)) return rule.ValidationMessage;

        if (cdr.ServiceGroup <= 0) return "ServiceGroup must be > 0";

        if (mediation.ServiceGroupConfigurations.TryGetValue(cdr.ServiceGroup, out var sg))
        {
            // chargeable (answered) calls use the answered checklist; failed calls the unanswered one.
            var checklist = (cdr.ChargingStatus ?? 0) == 1 ? sg.AnsweredChecklist : sg.UnansweredChecklist;
            foreach (var rule in checklist)
                if (!rule.Validate(cdr)) return rule.ValidationMessage;
        }
        return string.Empty;
    }
}
