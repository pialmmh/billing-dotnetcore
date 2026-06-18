using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Faithful PrefixMatcher: priority across tuples (outer), longest-prefix within a tuple
/// (middle), Category/SubCategory + [startdate, enddate) validity (inner); first match wins.</summary>
public class PrefixMatcherTests
{
    private static readonly DateTime Now = new(2026, 6, 19);

    [Fact]
    public void Longest_prefix_wins_within_a_tuple()
    {
        var tup = TestData.Tup(10, 1, 5, null, 0,
            TestData.Ra(1, 1.0m), TestData.Ra(17, 2.0m), TestData.Ra(171, 3.0m));
        var m = new PrefixMatcher(new[] { tup }, category: 1, subCategory: 1, Now);

        Assert.Equal(171, m.MatchPrefix("1712345")!.Prefix);   // longest (171)
        Assert.Equal(17, m.MatchPrefix("1799999")!.Prefix);    // 17 (no 171 match)
        Assert.Equal(1, m.MatchPrefix("1500000")!.Prefix);     // 1
        Assert.Null(m.MatchPrefix("9000000"));                  // none
    }

    [Fact]
    public void Lowest_priority_tuple_wins()
    {
        var hi = TestData.Tup(10, 1, 5, null, 5, TestData.Ra(1, 10.0m));
        var lo = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, 5.0m));
        var m = new PrefixMatcher(new[] { hi, lo }, 1, 1, Now);

        Assert.Equal(5.0m, m.MatchPrefix("1234")!.rateamount);   // priority-0 tuple wins
    }

    [Fact]
    public void Category_or_subcategory_mismatch_does_not_match()
    {
        var tup = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, 1.0m, category: 2));
        Assert.Null(new PrefixMatcher(new[] { tup }, category: 1, subCategory: 1, Now).MatchPrefix("1234"));
    }

    [Fact]
    public void Outside_validity_window_does_not_match()
    {
        var future = TestData.Ra(1, 1.0m, startdate: new DateTime(2027, 1, 1));
        var expired = TestData.Ra(2, 1.0m, enddate: new DateTime(2025, 1, 1));
        var m = new PrefixMatcher(new[] { TestData.Tup(10, 1, 5, null, 0, future, expired) }, 1, 1, Now);

        Assert.Null(m.MatchPrefix("1234"));   // not yet effective
        Assert.Null(m.MatchPrefix("2345"));   // expired
    }
}
