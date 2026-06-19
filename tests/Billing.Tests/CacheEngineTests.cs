using System.Text;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>The ported summary cache engine (AbstractCache + SummaryCache) over a tiny fake entity:
/// add/substract merge, the insert-vs-update change tracking, prev-load, and the write SQL through the
/// thin ISqlExecutor.</summary>
public class CacheEngineTests
{
    private sealed class FakeSummary : ICacheble<FakeSummary>, ISummary<FakeSummary, string>
    {
        public long id { get; set; }
        public string Key { get; set; } = "";
        public long Count { get; set; }
        public string GetTupleKey() => Key;
        public void Merge(FakeSummary o) => Count += o.Count;
        public void Multiply(int v) => Count *= v;
        public FakeSummary CloneWithFakeId() => new() { id = -1, Key = Key, Count = Count };
        public StringBuilder GetExtInsertValues() => new($"({id},'{Key}',{Count})");
        public StringBuilder GetUpdateCommand(Func<FakeSummary, string> w) => new($"update t set Count={Count}{w(this)}");
        public StringBuilder GetDeleteCommand(Func<FakeSummary, string> w) => new($"delete from t{w(this)}");
    }

    private sealed class CapturingExecutor : ISqlExecutor
    {
        public List<string> Sql { get; } = new();
        public int ExecuteNonQuery(string sql) { Sql.Add(sql); return 1; }
    }

    private sealed class FakeIds : IAutoIncrementManager
    {
        private long _n;
        public long GetNewCounter(string entityOrTableName) => ++_n;
    }

    private static SummaryCache<FakeSummary, string> NewCache() =>
        new("fake_sum", new FakeIds(),
            s => s.GetTupleKey(),
            s => s.GetExtInsertValues(),
            s => s.GetUpdateCommand(x => $" where id={x.id}"),
            s => s.GetDeleteCommand(x => $" where id={x.id}"),
            insertHeader: "insert into fake_sum (id,Key,Count) values ");

    [Fact]
    public void Add_new_then_add_same_key_merges_in_place_as_insert()
    {
        var cache = NewCache();
        cache.Merge(new FakeSummary { Key = "a", Count = 2 }, SummaryMergeType.Add, s => s.id > 0);
        cache.Merge(new FakeSummary { Key = "a", Count = 3 }, SummaryMergeType.Add, s => s.id > 0);

        Assert.Equal(1, cache.NumberOfInsertedItems);
        Assert.Equal(0, cache.NumberOfUpdatedItems);
        var row = cache.GetItems().Single();
        Assert.Equal(5, row.Count);     // 2 + 3
        Assert.True(row.id > 0);        // id stamped from the counter
    }

    [Fact]
    public void Populate_existing_then_add_marks_updated_not_inserted()
    {
        var cache = NewCache();
        cache.PopulateExisting(new FakeSummary { id = 100, Key = "a", Count = 10 });
        cache.Merge(new FakeSummary { Key = "a", Count = 3 }, SummaryMergeType.Add, s => s.id > 0);

        Assert.Equal(0, cache.NumberOfInsertedItems);
        Assert.Equal(1, cache.NumberOfUpdatedItems);
        Assert.Equal(13, cache.GetItems().Single().Count);
    }

    [Fact]
    public void Substract_negates_into_existing()
    {
        var cache = NewCache();
        cache.PopulateExisting(new FakeSummary { id = 100, Key = "a", Count = 10 });
        cache.Merge(new FakeSummary { Key = "a", Count = 4 }, SummaryMergeType.Substract, s => s.id > 0);

        Assert.Equal(6, cache.GetItems().Single().Count);   // 10 - 4
    }

    [Fact]
    public void Substract_with_no_existing_throws()
    {
        var cache = NewCache();
        Assert.Throws<NotSupportedException>(() =>
            cache.Merge(new FakeSummary { Key = "x", Count = 1 }, SummaryMergeType.Substract, s => s.id > 0));
    }

    [Fact]
    public void WriteAllChanges_inserts_new_and_updates_existing_then_clears()
    {
        var cache = NewCache();
        cache.PopulateExisting(new FakeSummary { id = 100, Key = "a", Count = 10 });
        cache.Merge(new FakeSummary { Key = "a", Count = 3 }, SummaryMergeType.Add, s => s.id > 0);   // -> update (13)
        cache.Merge(new FakeSummary { Key = "b", Count = 7 }, SummaryMergeType.Add, s => s.id > 0);   // -> insert

        var exec = new CapturingExecutor();
        cache.WriteAllChanges(exec);

        Assert.Contains(exec.Sql, s => s.StartsWith("insert into fake_sum") && s.Contains(",7)"));
        Assert.Contains(exec.Sql, s => s.StartsWith("update t set Count=13"));
        Assert.Equal(0, cache.NumberOfInsertedItems);    // cleared after write
        Assert.Equal(0, cache.NumberOfUpdatedItems);
    }
}
