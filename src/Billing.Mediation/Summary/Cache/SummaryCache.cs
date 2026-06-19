// Ported VERBATIM from legacy Mediation/Cache/SummaryCache.cs — the add/substract merge over
// AbstractCache. New key: clone the summary (so a later merge can't mutate the source), stamp a fresh id,
// insert. Existing key: merge (substract negates via Multiply(-1) first).
#nullable disable
using System;
using System.Text;
using MediationModel;

namespace TelcobrightMediation
{
    public class SummaryCache<TEntity, TKey> : AbstractCache<TEntity, TKey>
        where TEntity : ICacheble<TEntity>, ISummary<TEntity, TKey>
    {
        public override string EntityOrTableName { get; }
        private readonly object locker = new object();
        private IAutoIncrementManager AutoIncrementManager { get; }

        public SummaryCache(string entityName, IAutoIncrementManager autoIncrementManager,
            Func<TEntity, TKey> dictionaryKeyGenerator,
            Func<TEntity, StringBuilder> insertCommandGenerator,
            Func<TEntity, StringBuilder> updateCommandGenerator,
            Func<TEntity, StringBuilder> deleteCommandGenerator,
            string insertHeader)
            : base(dictionaryKeyGenerator, insertCommandGenerator, updateCommandGenerator,
                deleteCommandGenerator, insertHeader)
        {
            this.EntityOrTableName = entityName;
            this.AutoIncrementManager = autoIncrementManager;
        }

        public void Merge(TEntity newSummary, SummaryMergeType mergeType,
            Func<TEntity, bool> pdValidatationMethodForInsert)
        {
            lock (this.locker)
            {
                TKey key = newSummary.GetTupleKey();
                this.Cache.TryGetValue(key, out var existingSummary);
                if (existingSummary == null)
                {
                    if (mergeType == SummaryMergeType.Substract)
                        throw new NotSupportedException(
                            "Previous summary instance cannot be null for summary substraction.");
                    // clone so a later merge into the cached copy does not also mutate the source summary
                    var clonedSummary = (TEntity)newSummary.CloneWithFakeId();
                    clonedSummary.id = this.AutoIncrementManager.GetNewCounter(this.EntityOrTableName);
                    if (mergeType == SummaryMergeType.Add)
                        InsertWithKey(clonedSummary, key, pdValidatationMethodForInsert);
                    else
                        throw new NotSupportedException("Summary merge type must be add or substract.");
                }
                else
                {
                    if (mergeType == SummaryMergeType.Substract)
                        newSummary.Multiply(-1); // negate
                    this.UpdateThroughCache(this.DictionaryKeyGenerator(existingSummary),
                        e => e.Merge(newSummary));
                }
            }
        }
    }
}
