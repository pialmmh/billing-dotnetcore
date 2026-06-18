// Ported VERBATIM from legacy Models_Mediation/EntityExtensions/_Summary/ISummary.cs.
#nullable disable

namespace MediationModel
{
    public interface ISummary
    {
    }

    public interface ISummary<TEntity, TKey> : ISummary
    {
        long id { get; set; }
        TKey GetTupleKey();
        void Merge(TEntity newSummary);
        void Multiply(int value);

        // Summaries are merged in cache; cache.Insert without value copy shares the same reference for
        // the source summary entity and the mergeable version in cache. To prevent the source object
        // from being changed by a later merge, use CloneWithFakeId().
        TEntity CloneWithFakeId();
    }
}
