using System.Text.Json;
using MediationModel;

namespace Billing.Tests;

/// <summary>The ProcessCdrBatch gRPC path carries each cdr as JSON (the Kafka payload shape) and the handler
/// deserializes it into the full <c>cdr</c> POCO. These pin that mapping (PascalCase + camelCase keys,
/// decimals, datetimes, nullables).</summary>
public class CdrJsonDeserializeTests
{
    private static readonly JsonSerializerOptions Opts = new() { PropertyNameCaseInsensitive = true };

    [Fact]
    public void Cdr_json_deserializes_into_the_full_poco()
    {
        var json = """
        { "SwitchId": 1, "InPartnerId": 5, "TerminatingCalledNumber": "8801712345678",
          "OriginatingCallingNumber": "8801999000111", "DurationSec": 60, "ChargingStatus": 1,
          "StartTime": "2026-06-19T14:30:00", "AnswerTime": "2026-06-19T14:30:00",
          "CountryCode": "880", "Category": 1, "SubCategory": 1, "UniqueBillId": "uid-1" }
        """;

        var c = JsonSerializer.Deserialize<cdr>(json, Opts)!;

        Assert.Equal(5, c.InPartnerId);
        Assert.Equal("8801712345678", c.TerminatingCalledNumber);
        Assert.Equal(60m, c.DurationSec);
        Assert.Equal(1, c.ChargingStatus);
        Assert.Equal(new DateTime(2026, 6, 19, 14, 30, 0), c.StartTime);
        Assert.Equal("uid-1", c.UniqueBillId);
    }

    [Fact]
    public void Camel_case_keys_also_bind_and_unset_fields_default()
    {
        var c = JsonSerializer.Deserialize<cdr>(
            """{ "inPartnerId": 7, "terminatingCalledNumber": "8801711000000", "durationSec": 30 }""", Opts)!;

        Assert.Equal(7, c.InPartnerId);
        Assert.Equal(30m, c.DurationSec);
        Assert.Null(c.OutPartnerId);          // unset nullable stays null
        Assert.Equal(0, c.ServiceGroup);      // unset non-nullable defaults
    }
}
