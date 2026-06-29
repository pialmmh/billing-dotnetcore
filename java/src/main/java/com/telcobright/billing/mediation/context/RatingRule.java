// Ported from legacy Mediation/Context/RatingConfig.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.context;

/**
 * A RATING rule (legacy {@code RatingRule}): which service family to run, in which assignment direction, with
 * which digit rules. The engine iterates a service group's rules in order, resolving each family by
 * {@code IdServiceFamily} and charging the leg for {@code AssignDirection}. {@code DigitRulesData} is carried
 * for fidelity (the per-rule digit grouping) but not yet applied.
 *
 * <p>FAITHFUL-PORT NOTE: C# {@code sealed record RatingRule : Rule} -> Java record implementing the marker
 * interface {@link Rule}; constructed positionally (components in C# declaration order) where the C# used an
 * object initializer.</p>
 */
public record RatingRule(
        int IdServiceFamily,
        int AssignDirection,   // legacy: 1=Customer, 2=Supplier, 0=None
        String DigitRulesData
) implements Rule {
}
