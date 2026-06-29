// Split from legacy Mediation/Validation/MediationValidator.cs.
package com.telcobright.billing.mediation.validation;

/**
 * A mediation validation rule (legacy {@code IValidationRule<cdr>}) — one item in a qualification
 * checklist. Validate returns false to REJECT the cdr; ValidationMessage is the rejection reason written to
 * the cdr's {@code ErrorCode} and the cdr is routed to {@code cdrerror}. Kept generic so the same contract
 * validates other targets if needed (legacy was IValidationRule&lt;T&gt;).
 *
 * NOTE: the C# declared this contravariant ({@code IValidationRule<in T>}). Java has no declaration-site
 * variance, so the {@code in} is dropped; every use site here is {@code IValidationRule<cdr>}, so nothing
 * relies on the variance.
 */
public interface IValidationRule<T> {
    boolean Validate(T target);
    String ValidationMessage();
}
