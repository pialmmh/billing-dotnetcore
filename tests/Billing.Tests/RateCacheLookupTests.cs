using LibraryExtensions;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>Rate lookup THROUGH the per-day RateCache + the legacy PrefixMatcher: the cache loads the
/// day once (lazily), and PrefixMatcher longest-prefixes over that day's per-tuple prefix dictionaries.</summary>
public class RateCacheLookupTests
{
    private sealed class InMemoryRateLoader : IRateLoader
    {
        private readonly Func<DateRange, Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>> _load;
        public int Loads { get; private set; }
        public InMemoryRateLoader(Func<DateRange, Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>> load) => _load = load;
        public Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> LoadDay(DateRange dRange)
        {
            Loads++;
            return _load(dRange);
        }
    }

    private static readonly DateTime Today = new(2026, 6, 19);
    private static DateRange Day => new(Today, Today.AddDays(1));

    private static Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> OneTupleDay(DateRange d)
    {
        var prefixDic = new Dictionary<string, List<rateassign>>
        {
            ["1"] = new() { TestData.Ra(1, 1.0m) },
            ["17"] = new() { TestData.Ra(17, 2.0m) },
            ["171"] = new() { TestData.Ra(171, 3.0m) },
        };
        return new Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>(new TupleByPeriod.EqualityComparer())
        {
            [new TupleByPeriod { IdAssignmentTuple = 1, DRange = d, Priority = 0 }] = prefixDic,
        };
    }

    [Fact]
    public void Lookup_through_ratecache_longest_prefix_wins()
    {
        var cache = new RateCache(new InMemoryRateLoader(OneTupleDay));
        var tups = new List<TupleByPeriod> { new() { IdAssignmentTuple = 1, DRange = Day, Priority = 0 } };

        Assert.Equal(171, new PrefixMatcher(cache, "1712345", 1, 1, tups, Today).MatchPrefix().Prefix);
        Assert.Equal(17, new PrefixMatcher(cache, "1799999", 1, 1, tups, Today).MatchPrefix().Prefix);
        Assert.Equal(1, new PrefixMatcher(cache, "1500000", 1, 1, tups, Today).MatchPrefix().Prefix);
        Assert.Null(new PrefixMatcher(cache, "9000000", 1, 1, tups, Today).MatchPrefix());
    }

    [Fact]
    public void Ratecache_loads_the_day_once_then_serves_from_cache()
    {
        var loader = new InMemoryRateLoader(OneTupleDay);
        var cache = new RateCache(loader);

        cache.GetRateDictsByDay(Day);
        cache.GetRateDictsByDay(Day);   // second call: served from DateRangeWiseRateDic, no reload

        Assert.Equal(1, loader.Loads);
    }
}
