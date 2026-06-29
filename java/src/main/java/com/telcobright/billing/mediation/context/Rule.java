// Ported from legacy Mediation/Context/RatingConfig.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.context;

/**
 * The reusable base for a service group's configured rules — "just a rule". A service group holds an ordered
 * list of these ({@code ServiceGroupConfiguration.Rules}); each concrete kind is a rule the mediation engine
 * knows how to run. {@code RatingRule} is the only kind today; partner rules (and others) slot in as further
 * {@code Rule} subtypes without changing the configuration shape.
 *
 * <p>FAITHFUL-PORT NOTE: the C# {@code public abstract record Rule;} cannot be a Java record (records are
 * implicitly final and cannot be abstract or extended). It is ported as a plain marker interface so that
 * {@code RatingRule} (a record) can {@code implements Rule}; the C# {@code Rule} carried no state, so no
 * value-equality semantics are lost. It is intentionally NOT sealed (the C# base is extensible).</p>
 */
public interface Rule {
}
