// Ported from legacy Models_Mediation/EntityExtensions/ICachebale.cs + Mediation/Cache/{CachedItem,
// CachedSummary}.cs. The cache contract (per-row SQL builders). Trimmed to the members the summary path
// uses.
//
// NOTE (faithful-port deviation): the C# file declared BOTH a non-generic marker `interface ICacheble {}`
// AND `interface ICacheble<TEntity> : ICacheble`. Java cannot have two top-level types with the same name
// (the non-generic marker would be the raw type of the generic one), so the marker is subsumed into the
// raw form of this single generic interface. The other top-level types from the C# file
// (CachedItem, SummaryMergeType, IAutoIncrementManager) are split into their own files in this package.
package com.telcobright.billing.mediation.sql;

import java.util.function.Function;

/**
 * An entity the cache can persist: it builds its own INSERT-values / UPDATE / DELETE SQL fragments
 * (legacy ICacheble&lt;TEntity&gt;).
 */
public interface ICacheble<TEntity> {
    StringBuilder GetExtInsertValues();
    StringBuilder GetUpdateCommand(Function<TEntity, String> whereClauseMethod);
    StringBuilder GetDeleteCommand(Function<TEntity, String> whereClauseMethod);
}
