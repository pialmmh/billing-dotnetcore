package com.telcobright.billing.mediation.cdr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes ONE {@code summary_affected} OUTBOX row for the batch — the decoupled alternative to the inline
 * summary write. The batch's qualified cdrs (each with ALL its chargeable legs, {@link Entry} — the voice
 * summary reads the customer leg, the chargeable summary reads every leg) are serialised to JSON, gzipped,
 * then base64-encoded into the row's {@code data} column, and inserted through the batch's tx-bound
 * {@link ISqlExecutor}. So it commits / rolls back ATOMICALLY with the cdr + chargeable write. The
 * summary-service reads this row, decompresses it, and merges the deltas INCREMENTALLY per the row's
 * {@code op} ({@code add}, or {@code subtract} for corrections — a daily window has millions of cdrs, never
 * a full recompute).
 *
 * <p>base64 is used (not a 0x hex blob) so the value is a plain single-quoted SQL string with no escaping
 * needed (its alphabet has no quote/backslash), and it travels cleanly to the Java consumer (base64 → gunzip
 * → JSON). The whole batch is ONE row — the segmented multi-row writer is for the cdr/chargeable rows.</p>
 */
public final class SummaryOutboxWriter {
    private SummaryOutboxWriter() {}

    /** The entity tag on the outbox row + the summary-service's per-bean offset key. */
    public static final String DefaultEntityType = "cdr";

    /** Row operations: how the summary-service folds the row in. Normal batches are {@code add}; a
     * correction writes a {@code subtract} row holding the OLD rated values (negated by the consumer)
     * followed by an {@code add} row with the NEW ones, in the same transaction. */
    public static final String OpAdd = "add";
    public static final String OpSubtract = "subtract";

    private static final ObjectMapper Json = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true) // C# PropertyNameCaseInsensitive = true
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);          // C# DefaultIgnoreCondition = WhenWritingNull
    // (drop the cdr's many null fields → smaller blob)

    /** Write the batch's rated cdrs as a single compressed outbox row; returns rows affected (0 or 1). */
    public static int Write(ISqlExecutor sql, List<RatedCdr> rated, String entityType, String table, String op) {
        if (rated.isEmpty()) return 0;
        var data = Encode(rated);
        var stmt = "insert into " + table + " (entity_type, op, data) values ('" + entityType + "', '" + op
                + "', '" + data + "')";
        return sql.ExecuteNonQuery(stmt);
    }

    /** Write the batch's rated cdrs as a single compressed outbox row; returns rows affected (0 or 1). */
    public static int Write(ISqlExecutor sql, List<RatedCdr> rated, String entityType, String table) {
        return Write(sql, rated, entityType, table, OpAdd);
    }

    /** Write the batch's rated cdrs as a single compressed outbox row; returns rows affected (0 or 1). */
    public static int Write(ISqlExecutor sql, List<RatedCdr> rated, String entityType) {
        return Write(sql, rated, entityType, "summary_affected", OpAdd);
    }

    /** Write the batch's rated cdrs as a single compressed outbox row; returns rows affected (0 or 1). */
    public static int Write(ISqlExecutor sql, List<RatedCdr> rated) {
        return Write(sql, rated, DefaultEntityType, "summary_affected", OpAdd);
    }

    /** cdrs+chargeables → JSON → gzip → base64 (the {@code data} column value). */
    public static String Encode(List<RatedCdr> rated) {
        var entries = new ArrayList<Entry>(rated.size());
        for (var r : rated) entries.add(new Entry(r.Cdr(), r.Chargeables()));

        try {
            var jsonBytes = Json.writeValueAsBytes(entries);
            var ms = new ByteArrayOutputStream();
            // C# used CompressionLevel.Optimal + leaveOpen:true; Java's default GZIPOutputStream level is the
            // equivalent balanced level, and closing it only finishes the gzip stream (ByteArrayOutputStream.close
            // is a no-op), so ms.toByteArray() after the try-with-resources holds the complete gzip bytes.
            try (var gz = new GZIPOutputStream(ms)) {
                gz.write(jsonBytes, 0, jsonBytes.length);
            }
            return Base64.getEncoder().encodeToString(ms.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The inverse of {@link #Encode} — what the summary-service does (used here by tests to
     * prove the blob round-trips). */
    public static List<Entry> Decode(String base64Data) {
        var gz = Base64.getDecoder().decode(base64Data);
        try (var input = new ByteArrayInputStream(gz);
             var dz = new GZIPInputStream(input);
             var output = new ByteArrayOutputStream()) {
            dz.transferTo(output);
            return Json.readValue(output.toByteArray(),
                    Json.getTypeFactory().constructCollectionType(List.class, Entry.class));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
