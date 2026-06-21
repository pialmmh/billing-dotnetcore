// Ported from legacy Mediation/Cache/AbstractCache.cs.
// KEPT VERBATIM: the in-memory Cache + the Inserted/Updated/Deleted change-tracking, and
// Insert/InsertWithKey/UpdateThroughCache/Delete with their exact guard checks.
// ADAPTED: WriteAllChanges/WriteInserts/WriteUpdates/WriteDeletes run the generated SQL through an
// ISqlExecutor on the single shared MySqlConnection — the legacy DbWriterWithAccurateCount stored proc is
// gone (the executor returns the affected count directly), but the legacy CollectionSegmenter batching IS
// reused via Billing.Mediation.Sql.BatchSqlWriter, so any number of rows write in fixed-size segments. The
// legacy StaticExtInsertColumnParsedDic header is passed in as InsertHeader.
#nullable disable
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using Billing.Mediation.Sql;
using MediationModel;

namespace TelcobrightMediation
{
    public abstract class AbstractCache<TEntity, TKey> where TEntity : ICacheble<TEntity>
    {
        private readonly object locker = new object();

        protected AbstractCache(
            Func<TEntity, TKey> dictionaryKeyGenerator,
            Func<TEntity, StringBuilder> insertCommandGenerator,
            Func<TEntity, StringBuilder> updateCommandGenerator,
            Func<TEntity, StringBuilder> deleteCommandGenerator,
            string insertHeader)
        {
            this.DictionaryKeyGenerator = dictionaryKeyGenerator;
            this.InsertCommandGenerator = insertCommandGenerator;
            this.UpdateCommandGenerator = updateCommandGenerator;
            this.DeleteCommandGenerator = deleteCommandGenerator;
            this.InsertHeader = insertHeader;
        }

        public Func<TEntity, TKey> DictionaryKeyGenerator { get; protected set; }
        public Func<TEntity, StringBuilder> InsertCommandGenerator { get; protected set; }
        public Func<TEntity, StringBuilder> UpdateCommandGenerator { get; protected set; }
        public Func<TEntity, StringBuilder> DeleteCommandGenerator { get; protected set; }

        /// <summary>"insert into &lt;table&gt; (col1,...) values " — replaces the legacy StaticExtInsertColumnParsedDic.</summary>
        protected string InsertHeader { get; }
        public virtual string EntityOrTableName => typeof(TEntity).Name;

        protected ConcurrentDictionary<TKey, TEntity> Cache { get; } = new ConcurrentDictionary<TKey, TEntity>();
        protected readonly ConcurrentDictionary<TKey, TEntity> InsertedItems = new ConcurrentDictionary<TKey, TEntity>();
        protected readonly ConcurrentDictionary<TKey, TEntity> UpdatedItems = new ConcurrentDictionary<TKey, TEntity>();
        protected readonly ConcurrentDictionary<TKey, TEntity> DeletedItems = new ConcurrentDictionary<TKey, TEntity>();

        public bool Exists(TKey key) => this.Cache.ContainsKey(key);

        public virtual CachedItem<TKey, TEntity> GetItemByKey(TKey key)
        {
            this.Cache.TryGetValue(key, out var selectedItem);
            return selectedItem == null ? null : new CachedItem<TKey, TEntity>(key, selectedItem);
        }

        public virtual List<TEntity> GetItems() => this.Cache.Values.ToList();
        public virtual List<TEntity> GetInsertedItems() => this.InsertedItems.Values.ToList();
        public virtual List<TEntity> GetUpdatedItems() => this.UpdatedItems.Values.ToList();
        public virtual List<TEntity> GetDeletedItems() => this.DeletedItems.Values.ToList();
        public int NumberOfInsertedItems => this.InsertedItems.Count;
        public int NumberOfUpdatedItems => this.UpdatedItems.Count;
        public int NumberOfDeletedItems => this.DeletedItems.Count;

        /// <summary>Seed the cache with an EXISTING (already-persisted) row — the PopulatePrevSummary entry.
        /// It goes into the cache only, NOT InsertedItems, so a later merge marks it Updated (not Inserted).</summary>
        public virtual void PopulateExisting(TEntity existing)
        {
            lock (this.locker)
            {
                var key = this.DictionaryKeyGenerator(existing);
                if (this.Cache.TryAdd(key, existing) == false)
                    throw new Exception("Could not add existing item to cache while populating, probably duplicate item.");
            }
        }

        public virtual void Delete(TKey key)
        {
            lock (this.locker)
            {
                this.Cache.TryGetValue(key, out var toBeDeleted);
                if (toBeDeleted == null) throw new Exception("Item does not exist in cache.");
                if (this.DeletedItems.ContainsKey(key) == false)
                {
                    if (this.DeletedItems.TryAdd(key, toBeDeleted) == false)
                        throw new Exception("Could not add item to deleted items.");
                }
                this.Cache.TryRemove(this.DictionaryKeyGenerator(toBeDeleted), out _);
            }
        }

        public virtual void UpdateThroughCache(TKey key, Action<TEntity> entityUpdater)
        {
            lock (this.locker)
            {
                if (this.DeletedItems.ContainsKey(key) == true)
                    throw new Exception("Item to be updated already exists in deleted items which is not allowed");
                this.Cache.TryGetValue(key, out var entityToBeUpdated);
                if (entityToBeUpdated == null) throw new Exception("Item does not exist in cache.");
                entityUpdater.Invoke(entityToBeUpdated);
                if (this.InsertedItems.ContainsKey(key) == false && this.UpdatedItems.ContainsKey(key) == false)
                {
                    if (this.UpdatedItems.TryAdd(key, entityToBeUpdated) == false)
                        throw new Exception("Could not add item to updated items, probably duplicate item.");
                }
            }
        }

        public virtual CachedItem<TKey, TEntity> Insert(TEntity newItem, Func<TEntity, bool> pkValidationMethod)
        {
            lock (this.locker)
            {
                TKey key = this.DictionaryKeyGenerator(newItem);
                return InsertWithKey(newItem, key, pkValidationMethod);
            }
        }

        public virtual CachedItem<TKey, TEntity> InsertWithKey(TEntity newItem, TKey key, Func<TEntity, bool> pkValidationMethod)
        {
            lock (this.locker)
            {
                if (pkValidationMethod.Invoke(newItem) == false)
                    throw new Exception("Primary key is either missing or invalid.");
                if (this.UpdatedItems.ContainsKey(key) == true)
                    throw new Exception("Item to be inserted already exists in updated items which is not allowed.");
                if (this.DeletedItems.ContainsKey(key) == true)
                    throw new Exception("Item to be inserted already exists in deleted items which is not allowed.");
                if (this.Cache.TryAdd(key, newItem) == false)
                    throw new Exception("Could not add item to cache, probably duplicate item.");
                if (this.InsertedItems.TryAdd(key, newItem) == false)
                    throw new Exception("Could not add item to inserted items, probably duplicate item.");
                return new CachedItem<TKey, TEntity>(key, newItem);
            }
        }

        // ---- write engine: thin executor over the single shared connection ----

        public virtual void WriteAllChanges(ISqlExecutor executor, int segmentSize = BatchSqlWriter.DefaultSegmentSize)
        {
            lock (this.locker)
            {
                WriteDeletes(executor, segmentSize);
                WriteInserts(executor, segmentSize);
                WriteUpdates(executor, segmentSize);
            }
        }

        public virtual int WriteInserts(ISqlExecutor executor, int segmentSize = BatchSqlWriter.DefaultSegmentSize)
        {
            lock (this.locker)
            {
                if (this.InsertedItems.Any() == false) return 0;
                if (this.InsertCommandGenerator == null) throw new Exception("InsertCommandGenerator is not set and null.");
                var values = this.GetInsertedItems().Select(c => this.InsertCommandGenerator(c)).ToList();
                int affected = BatchSqlWriter.WriteInsertsInSegments(executor, this.InsertHeader, values, segmentSize);
                this.InsertedItems.Clear();
                return affected;
            }
        }

        public virtual void WriteUpdates(ISqlExecutor executor, int segmentSize = BatchSqlWriter.DefaultSegmentSize)
        {
            lock (this.locker)
            {
                if (this.UpdatedItems.Any() == false) return;
                if (this.UpdateCommandGenerator == null) throw new Exception("UpdateCommandGenerator is null/not defined.");
                var statements = this.GetUpdatedItems().Select(c => this.UpdateCommandGenerator(c).ToString()).ToList();
                BatchSqlWriter.WriteStatementsInSegments(executor, statements, segmentSize);
                this.UpdatedItems.Clear();
            }
        }

        public virtual void WriteDeletes(ISqlExecutor executor, int segmentSize = BatchSqlWriter.DefaultSegmentSize)
        {
            lock (this.locker)
            {
                if (this.DeletedItems.Any() == false) return;
                if (this.DeleteCommandGenerator == null) throw new Exception("DeleteCommandGenerator is null/not defined.");
                var statements = this.GetDeletedItems().Select(c => this.DeleteCommandGenerator(c).ToString()).ToList();
                BatchSqlWriter.WriteStatementsInSegments(executor, statements, segmentSize);
                this.DeletedItems.Clear();
            }
        }
    }
}
