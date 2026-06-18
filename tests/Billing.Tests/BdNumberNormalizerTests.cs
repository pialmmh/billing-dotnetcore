using Billing.Mediation.ServiceGroups;

namespace Billing.Tests;

/// <summary>Golden checks for BD number normalization (the strip-+/0/00880/880880/880 logic shared by
/// the SgDomOffnet* detectors).</summary>
public class BdNumberNormalizerTests
{
    [Theory]
    [InlineData("01712345678", "1712345678")]       // leading trunk 0
    [InlineData("8801712345678", "1712345678")]     // 880 country code
    [InlineData("008801712345678", "1712345678")]   // 00880 IDD form
    [InlineData("880880171234", "171234")]          // 880880 teletalk quirk
    [InlineData("+8801712345678", "1712345678")]    // leading + then 880
    [InlineData("1712345678", "1712345678")]        // already national-significant
    public void Normalizes(string raw, string expected)
        => Assert.Equal(expected, BdNumberNormalizer.Normalize(raw));

    [Fact]
    public void Blank_passes_through_safely()
    {
        Assert.Equal("", BdNumberNormalizer.Normalize(null));
        Assert.Equal("", BdNumberNormalizer.Normalize(""));
        Assert.Equal("   ", BdNumberNormalizer.Normalize("   "));   // whitespace left untouched
    }
}
