package com.telcobright.billing.mediation.engine.models;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The ProcessCdrBatch gRPC path carries each cdr as JSON (the Kafka payload shape) and the handler
 * deserializes it into the full {@code cdr} POCO. These pin that mapping (PascalCase + camelCase keys,
 * decimals, datetimes, nullables).
 *
 * <p>Faithful-port note: the C# {@code JsonSerializerOptions{PropertyNameCaseInsensitive=true}} maps to the
 * Jackson {@code ObjectMapper} the handler builds (see api/internal/ProcessCdrBatchHandler): case-insensitive
 * properties + JSR-310 dates (System.Text.Json handled DateTime natively; Jackson needs JavaTimeModule) +
 * ignore-unknown.</p>
 */
class CdrJsonDeserializeTests {
    // case-insensitive, ignore-unknown, JSR-310 dates — mirrors ProcessCdrBatchHandler.CdrJson.
    private static final ObjectMapper Opts = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void Cdr_json_deserializes_into_the_full_poco() throws Exception {
        String json = """
                { "SwitchId": 1, "InPartnerId": 5, "TerminatingCalledNumber": "8801712345678",
                  "OriginatingCallingNumber": "8801999000111", "DurationSec": 60, "ChargingStatus": 1,
                  "StartTime": "2026-06-19T14:30:00", "AnswerTime": "2026-06-19T14:30:00",
                  "CountryCode": "880", "Category": 1, "SubCategory": 1, "UniqueBillId": "uid-1" }
                """;

        cdr c = Opts.readValue(json, cdr.class);

        assertEquals(5, c.InPartnerId);
        assertEquals("8801712345678", c.TerminatingCalledNumber);
        assertEquals(0, new BigDecimal("60").compareTo(c.DurationSec));
        assertEquals(1, c.ChargingStatus);
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 30, 0), c.StartTime);
        assertEquals("uid-1", c.UniqueBillId);
    }

    @Test
    void Camel_case_keys_also_bind_and_unset_fields_default() throws Exception {
        cdr c = Opts.readValue(
                "{ \"inPartnerId\": 7, \"terminatingCalledNumber\": \"8801711000000\", \"durationSec\": 30 }",
                cdr.class);

        assertEquals(7, c.InPartnerId);
        assertEquals(0, new BigDecimal("30").compareTo(c.DurationSec));
        assertNull(c.OutPartnerId);          // unset nullable stays null
        assertEquals(0, c.ServiceGroup);     // unset non-nullable defaults
    }
}
