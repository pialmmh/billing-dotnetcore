// Ported VERBATIM from legacy Mediation/Cache/SummaryCache.cs — the add/substract merge over
// AbstractCache. New key: clone the summary (so a later merge can't mutate the source), stamp a fresh id,
// insert. Existing key: merge (substract negates via Multiply(-1) first).
package com.telcobright.billing.mediation.summary.cache;

import java.util.function.Function;
import java.util.function.Predicate;

import com.telcobright.billing.mediation.engine.models.ISummary;

public class SummaryCache<TEntity extends ICacheble<TEntity> & ISummary<TEntity, TKey>, TKey>
        extends AbstractCache<TEntity, TKey> {

    private final String entityOrTableName;
    private final Object locker = new Object();
    private final IAutoIncrementManager AutoIncrementManager;

    public SummaryCache(String entityName, IAutoIncrementManager autoIncrementManager,
            Function<TEntity, TKey> dictionaryKeyGenerator,
            Function<TEntity, StringBuilder> insertCommandGenerator,
            Function<TEntity, StringBuilder> updateCommandGenerator,
            Function<TEntity, StringBuilder> deleteCommandGenerator,
            String insertHeader) {
        super(dictionaryKeyGenerator, insertCommandGenerator, updateCommandGenerator,
                deleteCommandGenerator, insertHeader);
        this.entityOrTableName = entityName;
        this.AutoIncrementManager = autoIncrementManager;
    }

    @Override
    public String EntityOrTableName() {
        return this.entityOrTableName;
    }

    public void Merge(TEntity newSummary, SummaryMergeType mergeType,
            Predicate<TEntity> pdValidatationMethodForInsert) {
        synchronized (this.locker) {
            TKey key = newSummary.GetTupleKey();
            TEntity existingSummary = this.Cache.get(key);
            if (existingSummary == null) {
                if (mergeType == SummaryMergeType.Substract)
                    throw new UnsupportedOperationException(
                            "Previous summary instance cannot be null for summary substraction.");
                // clone so a later merge into the cached copy does not also mutate the source summary
                TEntity clonedSummary = newSummary.CloneWithFakeId();
                clonedSummary.setId(this.AutoIncrementManager.GetNewCounter(this.EntityOrTableName()));
                if (mergeType == SummaryMergeType.Add)
                    InsertWithKey(clonedSummary, key, pdValidatationMethodForInsert);
                else
                    throw new UnsupportedOperationException("Summary merge type must be add or substract.");
            } else {
                if (mergeType == SummaryMergeType.Substract)
                    newSummary.Multiply(-1); // negate
                this.UpdateThroughCache(this.DictionaryKeyGenerator.apply(existingSummary),
                        e -> e.Merge(newSummary));
            }
        }
    }
}
