// Ported from legacy Mediation/Cache/AbstractCache.cs.
// KEPT VERBATIM: the in-memory Cache + the Inserted/Updated/Deleted change-tracking, and
// Insert/InsertWithKey/UpdateThroughCache/Delete with their exact guard checks.
// ADAPTED: WriteAllChanges/WriteInserts/WriteUpdates/WriteDeletes run the generated SQL through an
// ISqlExecutor on the single shared MySqlConnection — the legacy DbWriterWithAccurateCount stored proc is
// gone (the executor returns the affected count directly), but the legacy CollectionSegmenter batching IS
// reused via Billing.Mediation.Sql.BatchSqlWriter, so any number of rows write in fixed-size segments. The
// legacy StaticExtInsertColumnParsedDic header is passed in as InsertHeader.
package com.telcobright.billing.mediation.summary.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

public abstract class AbstractCache<TEntity extends ICacheble<TEntity>, TKey> {
    private final Object locker = new Object();

    protected AbstractCache(
            Function<TEntity, TKey> dictionaryKeyGenerator,
            Function<TEntity, StringBuilder> insertCommandGenerator,
            Function<TEntity, StringBuilder> updateCommandGenerator,
            Function<TEntity, StringBuilder> deleteCommandGenerator,
            String insertHeader) {
        this.DictionaryKeyGenerator = dictionaryKeyGenerator;
        this.InsertCommandGenerator = insertCommandGenerator;
        this.UpdateCommandGenerator = updateCommandGenerator;
        this.DeleteCommandGenerator = deleteCommandGenerator;
        this.InsertHeader = insertHeader;
    }

    public final Function<TEntity, TKey> DictionaryKeyGenerator;
    public final Function<TEntity, StringBuilder> InsertCommandGenerator;
    public final Function<TEntity, StringBuilder> UpdateCommandGenerator;
    public final Function<TEntity, StringBuilder> DeleteCommandGenerator;

    /** "insert into &lt;table&gt; (col1,...) values " — replaces the legacy StaticExtInsertColumnParsedDic. */
    protected final String InsertHeader;

    public String EntityOrTableName() {
        // C# returned typeof(TEntity).Name, which is unavailable under Java generic erasure. This base
        // version is never invoked in practice (the only concrete subclass, SummaryCache, overrides it).
        throw new UnsupportedOperationException(
                "typeof(TEntity).Name is unavailable under Java generic erasure; subclasses must override EntityOrTableName().");
    }

    protected final ConcurrentMap<TKey, TEntity> Cache = new ConcurrentHashMap<>();
    protected final ConcurrentMap<TKey, TEntity> InsertedItems = new ConcurrentHashMap<>();
    protected final ConcurrentMap<TKey, TEntity> UpdatedItems = new ConcurrentHashMap<>();
    protected final ConcurrentMap<TKey, TEntity> DeletedItems = new ConcurrentHashMap<>();

    public boolean Exists(TKey key) {
        return this.Cache.containsKey(key);
    }

    public CachedItem<TKey, TEntity> GetItemByKey(TKey key) {
        TEntity selectedItem = this.Cache.get(key);
        return selectedItem == null ? null : new CachedItem<TKey, TEntity>(key, selectedItem);
    }

    public List<TEntity> GetItems() {
        return new ArrayList<>(this.Cache.values());
    }

    public List<TEntity> GetInsertedItems() {
        return new ArrayList<>(this.InsertedItems.values());
    }

    public List<TEntity> GetUpdatedItems() {
        return new ArrayList<>(this.UpdatedItems.values());
    }

    public List<TEntity> GetDeletedItems() {
        return new ArrayList<>(this.DeletedItems.values());
    }

    public int NumberOfInsertedItems() {
        return this.InsertedItems.size();
    }

    public int NumberOfUpdatedItems() {
        return this.UpdatedItems.size();
    }

    public int NumberOfDeletedItems() {
        return this.DeletedItems.size();
    }

    /**
     * Seed the cache with an EXISTING (already-persisted) row — the PopulatePrevSummary entry. It goes into
     * the cache only, NOT InsertedItems, so a later merge marks it Updated (not Inserted).
     */
    public void PopulateExisting(TEntity existing) {
        synchronized (this.locker) {
            TKey key = this.DictionaryKeyGenerator.apply(existing);
            if (this.Cache.putIfAbsent(key, existing) != null)
                throw new RuntimeException("Could not add existing item to cache while populating, probably duplicate item.");
        }
    }

    public void Delete(TKey key) {
        synchronized (this.locker) {
            TEntity toBeDeleted = this.Cache.get(key);
            if (toBeDeleted == null) throw new RuntimeException("Item does not exist in cache.");
            if (this.DeletedItems.containsKey(key) == false) {
                if (this.DeletedItems.putIfAbsent(key, toBeDeleted) != null)
                    throw new RuntimeException("Could not add item to deleted items.");
            }
            this.Cache.remove(this.DictionaryKeyGenerator.apply(toBeDeleted));
        }
    }

    public void UpdateThroughCache(TKey key, Consumer<TEntity> entityUpdater) {
        synchronized (this.locker) {
            if (this.DeletedItems.containsKey(key) == true)
                throw new RuntimeException("Item to be updated already exists in deleted items which is not allowed");
            TEntity entityToBeUpdated = this.Cache.get(key);
            if (entityToBeUpdated == null) throw new RuntimeException("Item does not exist in cache.");
            entityUpdater.accept(entityToBeUpdated);
            if (this.InsertedItems.containsKey(key) == false && this.UpdatedItems.containsKey(key) == false) {
                if (this.UpdatedItems.putIfAbsent(key, entityToBeUpdated) != null)
                    throw new RuntimeException("Could not add item to updated items, probably duplicate item.");
            }
        }
    }

    public CachedItem<TKey, TEntity> Insert(TEntity newItem, Predicate<TEntity> pkValidationMethod) {
        synchronized (this.locker) {
            TKey key = this.DictionaryKeyGenerator.apply(newItem);
            return InsertWithKey(newItem, key, pkValidationMethod);
        }
    }

    public CachedItem<TKey, TEntity> InsertWithKey(TEntity newItem, TKey key, Predicate<TEntity> pkValidationMethod) {
        synchronized (this.locker) {
            if (pkValidationMethod.test(newItem) == false)
                throw new RuntimeException("Primary key is either missing or invalid.");
            if (this.UpdatedItems.containsKey(key) == true)
                throw new RuntimeException("Item to be inserted already exists in updated items which is not allowed.");
            if (this.DeletedItems.containsKey(key) == true)
                throw new RuntimeException("Item to be inserted already exists in deleted items which is not allowed.");
            if (this.Cache.putIfAbsent(key, newItem) != null)
                throw new RuntimeException("Could not add item to cache, probably duplicate item.");
            if (this.InsertedItems.putIfAbsent(key, newItem) != null)
                throw new RuntimeException("Could not add item to inserted items, probably duplicate item.");
            return new CachedItem<TKey, TEntity>(key, newItem);
        }
    }

    // ---- write engine: thin executor over the single shared connection ----

    public void WriteAllChanges(ISqlExecutor executor) {
        WriteAllChanges(executor, BatchSqlWriter.DefaultSegmentSize);
    }

    public void WriteAllChanges(ISqlExecutor executor, int segmentSize) {
        synchronized (this.locker) {
            WriteDeletes(executor, segmentSize);
            WriteInserts(executor, segmentSize);
            WriteUpdates(executor, segmentSize);
        }
    }

    public int WriteInserts(ISqlExecutor executor) {
        return WriteInserts(executor, BatchSqlWriter.DefaultSegmentSize);
    }

    public int WriteInserts(ISqlExecutor executor, int segmentSize) {
        synchronized (this.locker) {
            if (this.InsertedItems.isEmpty() == true) return 0;
            if (this.InsertCommandGenerator == null) throw new RuntimeException("InsertCommandGenerator is not set and null.");
            List<StringBuilder> values = this.GetInsertedItems().stream()
                    .map(c -> this.InsertCommandGenerator.apply(c)).collect(Collectors.toList());
            int affected = BatchSqlWriter.WriteInsertsInSegments(executor, this.InsertHeader, values, segmentSize);
            this.InsertedItems.clear();
            return affected;
        }
    }

    public void WriteUpdates(ISqlExecutor executor) {
        WriteUpdates(executor, BatchSqlWriter.DefaultSegmentSize);
    }

    public void WriteUpdates(ISqlExecutor executor, int segmentSize) {
        synchronized (this.locker) {
            if (this.UpdatedItems.isEmpty() == true) return;
            if (this.UpdateCommandGenerator == null) throw new RuntimeException("UpdateCommandGenerator is null/not defined.");
            List<String> statements = this.GetUpdatedItems().stream()
                    .map(c -> this.UpdateCommandGenerator.apply(c).toString()).collect(Collectors.toList());
            BatchSqlWriter.WriteStatementsInSegments(executor, statements, segmentSize);
            this.UpdatedItems.clear();
        }
    }

    public void WriteDeletes(ISqlExecutor executor) {
        WriteDeletes(executor, BatchSqlWriter.DefaultSegmentSize);
    }

    public void WriteDeletes(ISqlExecutor executor, int segmentSize) {
        synchronized (this.locker) {
            if (this.DeletedItems.isEmpty() == true) return;
            if (this.DeleteCommandGenerator == null) throw new RuntimeException("DeleteCommandGenerator is null/not defined.");
            List<String> statements = this.GetDeletedItems().stream()
                    .map(c -> this.DeleteCommandGenerator.apply(c).toString()).collect(Collectors.toList());
            BatchSqlWriter.WriteStatementsInSegments(executor, statements, segmentSize);
            this.DeletedItems.clear();
        }
    }
}
