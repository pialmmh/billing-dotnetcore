// Faithful port of tests/Billing.Tests/CacheEngineTests.cs (xUnit -> JUnit 5).
// Placed in the same package as the SUT (SummaryCache/AbstractCache) per RULE T0.
package com.telcobright.billing.mediation.summary.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.ISummary;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

/**
 * The ported summary cache engine (AbstractCache + SummaryCache) over a tiny fake entity: add/substract
 * merge, the insert-vs-update change tracking, prev-load, and the write SQL through the thin ISqlExecutor.
 */
class CacheEngineTests {

    // The C# `FakeSummary` captured emitted SQL via small string builders; ported here as a test helper that
    // implements both cache contracts. NOTE: C#'s `long id { get; set; }` (the ISummary property) is exposed
    // in Java as getId()/setId(long) over a public `id` field (the test reads row.id directly).
    static final class FakeSummary implements ICacheble<FakeSummary>, ISummary<FakeSummary, String> {
        public long id;
        public String Key = "";
        public long Count;

        @Override public long getId() { return id; }
        @Override public void setId(long value) { id = value; }
        @Override public String GetTupleKey() { return Key; }
        @Override public void Merge(FakeSummary o) { Count += o.Count; }
        @Override public void Multiply(int v) { Count *= v; }
        @Override public FakeSummary CloneWithFakeId() {
            FakeSummary f = new FakeSummary();
            f.id = -1; f.Key = Key; f.Count = Count;
            return f;
        }
        @Override public StringBuilder GetExtInsertValues() {
            return new StringBuilder("(" + id + ",'" + Key + "'," + Count + ")");
        }
        @Override public StringBuilder GetUpdateCommand(Function<FakeSummary, String> w) {
            return new StringBuilder("update t set Count=" + Count + w.apply(this));
        }
        @Override public StringBuilder GetDeleteCommand(Function<FakeSummary, String> w) {
            return new StringBuilder("delete from t" + w.apply(this));
        }
    }

    // Captures each executed statement; reports 1 affected per call (the C# fake).
    static final class CapturingExecutor implements ISqlExecutor {
        final List<String> Sql = new ArrayList<>();
        @Override public int ExecuteNonQuery(String sql) { Sql.add(sql); return 1; }
    }

    static final class FakeIds implements IAutoIncrementManager {
        private long _n;
        @Override public long GetNewCounter(String entityOrTableName) { return ++_n; }
    }

    private static SummaryCache<FakeSummary, String> NewCache() {
        return new SummaryCache<FakeSummary, String>("fake_sum", new FakeIds(),
                s -> s.GetTupleKey(),
                s -> s.GetExtInsertValues(),
                s -> s.GetUpdateCommand(x -> " where id=" + x.id),
                s -> s.GetDeleteCommand(x -> " where id=" + x.id),
                "insert into fake_sum (id,Key,Count) values ");
    }

    private static FakeSummary fake(String key, long count) {
        FakeSummary f = new FakeSummary();
        f.Key = key; f.Count = count;
        return f;
    }

    // LINQ .Single(): assert exactly one element and return it.
    private static <T> T single(List<T> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    @Test
    void Add_new_then_add_same_key_merges_in_place_as_insert() {
        var cache = NewCache();
        cache.Merge(fake("a", 2), SummaryMergeType.Add, s -> s.id > 0);
        cache.Merge(fake("a", 3), SummaryMergeType.Add, s -> s.id > 0);

        assertEquals(1, cache.NumberOfInsertedItems());
        assertEquals(0, cache.NumberOfUpdatedItems());
        var row = single(cache.GetItems());
        assertEquals(5L, row.Count);     // 2 + 3
        assertTrue(row.id > 0);          // id stamped from the counter
    }

    @Test
    void Populate_existing_then_add_marks_updated_not_inserted() {
        var cache = NewCache();
        FakeSummary existing = new FakeSummary();
        existing.id = 100; existing.Key = "a"; existing.Count = 10;
        cache.PopulateExisting(existing);
        cache.Merge(fake("a", 3), SummaryMergeType.Add, s -> s.id > 0);

        assertEquals(0, cache.NumberOfInsertedItems());
        assertEquals(1, cache.NumberOfUpdatedItems());
        assertEquals(13L, single(cache.GetItems()).Count);
    }

    @Test
    void Substract_negates_into_existing() {
        var cache = NewCache();
        FakeSummary existing = new FakeSummary();
        existing.id = 100; existing.Key = "a"; existing.Count = 10;
        cache.PopulateExisting(existing);
        cache.Merge(fake("a", 4), SummaryMergeType.Substract, s -> s.id > 0);

        assertEquals(6L, single(cache.GetItems()).Count);   // 10 - 4
    }

    @Test
    void Substract_with_no_existing_throws() {
        var cache = NewCache();
        assertThrows(UnsupportedOperationException.class, () ->
                cache.Merge(fake("x", 1), SummaryMergeType.Substract, s -> s.id > 0));
    }

    @Test
    void WriteAllChanges_inserts_new_and_updates_existing_then_clears() {
        var cache = NewCache();
        FakeSummary existing = new FakeSummary();
        existing.id = 100; existing.Key = "a"; existing.Count = 10;
        cache.PopulateExisting(existing);
        cache.Merge(fake("a", 3), SummaryMergeType.Add, s -> s.id > 0);   // -> update (13)
        cache.Merge(fake("b", 7), SummaryMergeType.Add, s -> s.id > 0);   // -> insert

        var exec = new CapturingExecutor();
        cache.WriteAllChanges(exec);

        assertTrue(exec.Sql.stream().anyMatch(s -> s.startsWith("insert into fake_sum") && s.contains(",7)")));
        assertTrue(exec.Sql.stream().anyMatch(s -> s.startsWith("update t set Count=13")));
        assertEquals(0, cache.NumberOfInsertedItems());    // cleared after write
        assertEquals(0, cache.NumberOfUpdatedItems());
    }
}
