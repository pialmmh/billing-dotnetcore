// Ported from legacy Models_Mediation/EntityExtensions/ICachebale.cs + Mediation/Cache/{CachedItem,
// CachedSummary}.cs. The cache contract (per-row SQL builders), the cached-item wrapper, the merge type,
// and the new-id source. Trimmed to the members the summary path uses.
#nullable disable
using System;
using System.Text;

namespace MediationModel
{
    public interface ICacheble { }

    /// <summary>An entity the cache can persist: it builds its own INSERT-values / UPDATE / DELETE SQL
    /// fragments (legacy ICacheble&lt;TEntity&gt;).</summary>
    public interface ICacheble<TEntity> : ICacheble
    {
        StringBuilder GetExtInsertValues();
        StringBuilder GetUpdateCommand(Func<TEntity, string> whereClauseMethod);
        StringBuilder GetDeleteCommand(Func<TEntity, string> whereClauseMethod);
    }

    /// <summary>Add (new CDRs) or Substract (erased/reprocessed CDRs) when merging a summary (legacy spelling kept).</summary>
    public enum SummaryMergeType { Add, Substract }

    /// <summary>What the cache returns for a keyed item (legacy CachedItem).</summary>
    public sealed class CachedItem<TKey, TEntity>
    {
        public TKey Key { get; }
        public TEntity Entity { get; }
        public CachedItem(TKey key, TEntity entity) { Key = key; Entity = entity; }
    }

    /// <summary>Source of new row ids per entity/table (legacy IAutoIncrementManager; keyed by table name
    /// here instead of the AutoIncrementCounterType enum).</summary>
    public interface IAutoIncrementManager
    {
        long GetNewCounter(string entityOrTableName);
    }
}
