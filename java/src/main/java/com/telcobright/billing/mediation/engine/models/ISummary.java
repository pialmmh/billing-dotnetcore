// Ported VERBATIM from legacy Models_Mediation/EntityExtensions/_Summary/ISummary.cs.
package com.telcobright.billing.mediation.engine.models;

/**
 * Faithful port of the C# {@code ISummary<TEntity, TKey>}.
 *
 * <p>FAITHFUL-PORT ADAPTATION: the C# file declares TWO types — a non-generic marker
 * {@code public interface ISummary { }} and {@code public interface ISummary<TEntity, TKey> : ISummary}.
 * Java forbids two top-level types sharing the name {@code ISummary} (generic arity does not disambiguate
 * type names as it does in C#), so only the generic interface is kept. The non-generic marker is never
 * referenced anywhere in the codebase, and Java's raw type {@code ISummary} stands in for it if ever needed.</p>
 *
 * <p>FAITHFUL-PORT ADAPTATION: the C# member {@code long id { get; set; }} is an interface PROPERTY. Java
 * interfaces cannot declare a field, and a generic bound ({@code SummaryCache} does
 * {@code clonedSummary.id = ...} on a {@code TEntity}) can only reach members through METHODS, so the
 * property is exposed here as {@code getId()/setId(long)}. The concrete {@code AbstractCdrSummary} keeps a
 * public {@code id} field (RULE 4) and implements these accessors over it.</p>
 */
public interface ISummary<TEntity, TKey> {
    long getId();
    void setId(long value);
    TKey GetTupleKey();
    void Merge(TEntity newSummary);
    void Multiply(int value);

    // Summaries are merged in cache; cache.Insert without value copy shares the same reference for
    // the source summary entity and the mergeable version in cache. To prevent the source object
    // from being changed by a later merge, use CloneWithFakeId().
    TEntity CloneWithFakeId();
}
