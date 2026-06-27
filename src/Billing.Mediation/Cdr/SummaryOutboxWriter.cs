using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Serialization;
using Billing.Mediation.Sql;
using MediationModel;

namespace Billing.Mediation.Cdr;

/// <summary>How a batch's summaries are persisted.</summary>
public enum SummaryMode
{
    /// <summary>billing-core rolls up + writes the summaries itself, inline, in the batch transaction
    /// (the legacy behaviour — <c>CdrSummaryContext</c>).</summary>
    Inline,

    /// <summary>billing-core writes the batch (compressed) to the <c>summary_affected</c> OUTBOX instead of
    /// summarising; the decoupled summary-service consumes that row and writes the summaries. The outbox row
    /// is written in the SAME transaction as the cdr/chargeable write, so it is atomic with them — which is
    /// what sidesteps the dual-write (MySQL + Kafka can't commit together) problem.</summary>
    Outbox,
}

/// <summary>
/// Writes ONE <c>summary_affected</c> OUTBOX row for the batch — the decoupled alternative to the inline
/// summary write. The batch's qualified cdrs (each with its customer-leg <see cref="acc_chargeable"/> — what
/// the summary builder reads) are serialised to JSON, gzipped, then base64-encoded into the row's <c>data</c>
/// column, and inserted through the batch's tx-bound <see cref="ISqlExecutor"/>. So it commits / rolls back
/// ATOMICALLY with the cdr + chargeable write. The summary-service reads this row, decompresses it, and merges
/// the deltas INCREMENTALLY (a daily window has millions of cdrs — never a full recompute).
///
/// <para>base64 is used (not a 0x hex blob) so the value is a plain single-quoted SQL string with no escaping
/// needed (its alphabet has no quote/backslash), and it travels cleanly to the Java consumer (base64 → gunzip
/// → JSON). The whole batch is ONE row — the segmented multi-row writer is for the cdr/chargeable rows.</para>
/// </summary>
public static class SummaryOutboxWriter
{
    /// <summary>The entity tag on the outbox row + the summary-service's per-bean offset key.</summary>
    public const string DefaultEntityType = "cdr";

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,   // drop the cdr's many null fields → smaller blob
    };

    /// <summary>One outbox entry: the rated cdr + its customer-leg chargeable (the pair the summary builder
    /// consumes). Mirrors the Java side's blob contract.</summary>
    public sealed record Entry(cdr Cdr, acc_chargeable? Customer);

    /// <summary>Write the batch's rated cdrs as a single compressed outbox row; returns rows affected (0 or 1).</summary>
    public static int Write(ISqlExecutor sql, IReadOnlyList<RatedCdr> rated,
        string entityType = DefaultEntityType, string table = "summary_affected")
    {
        if (rated.Count == 0) return 0;
        var data = Encode(rated);
        var stmt = $"insert into {table} (entity_type, data) values ('{entityType}', '{data}')";
        return sql.ExecuteNonQuery(stmt);
    }

    /// <summary>cdrs+chargeables → JSON → gzip → base64 (the <c>data</c> column value).</summary>
    public static string Encode(IReadOnlyList<RatedCdr> rated)
    {
        var entries = new List<Entry>(rated.Count);
        foreach (var r in rated) entries.Add(new Entry(r.Cdr, r.Customer));

        var jsonBytes = JsonSerializer.SerializeToUtf8Bytes(entries, Json);
        using var ms = new MemoryStream();
        using (var gz = new GZipStream(ms, CompressionLevel.Optimal, leaveOpen: true))
            gz.Write(jsonBytes, 0, jsonBytes.Length);
        return Convert.ToBase64String(ms.ToArray());
    }

    /// <summary>The inverse of <see cref="Encode"/> — what the summary-service does (used here by tests to
    /// prove the blob round-trips).</summary>
    public static IReadOnlyList<Entry> Decode(string base64Data)
    {
        var gz = Convert.FromBase64String(base64Data);
        using var input = new MemoryStream(gz);
        using var dz = new GZipStream(input, CompressionMode.Decompress);
        using var output = new MemoryStream();
        dz.CopyTo(output);
        return JsonSerializer.Deserialize<List<Entry>>(output.ToArray(), Json)!;
    }
}
