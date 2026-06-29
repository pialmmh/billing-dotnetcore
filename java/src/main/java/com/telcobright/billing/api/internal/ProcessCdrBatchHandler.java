package com.telcobright.billing.api.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.grpc.CdrBatchRequest;
import com.telcobright.billing.grpc.CdrBatchResult;
import com.telcobright.billing.mediation.engine.models.cdr;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts the gRPC {@code ProcessCdrBatch} RPC to the {@link CdrProcessor} bean: deserialize each cdr from its
 * JSON (the Kafka payload shape) into the full {@code cdr} POCO, hand the batch to the processor (which
 * resolves the tenant's config from config-manager and mediates + writes it), then map the outcome back to
 * the proto reply. All the processing lives in the bean; this is just the proto boundary.
 */
@Singleton
public class ProcessCdrBatchHandler {
    // case-insensitive, ignore-unknown, JSR-310 dates — mirrors the .NET JsonSerializerOptions{PropertyNameCaseInsensitive=true}.
    private static final ObjectMapper CdrJson = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CdrProcessor _cdrProcessor;

    @Inject
    public ProcessCdrBatchHandler(CdrProcessor cdrProcessor) {
        this._cdrProcessor = cdrProcessor;
    }

    public CdrBatchResult Handle(CdrBatchRequest request) {
        List<cdr> cdrs;
        try {
            cdrs = new ArrayList<>(request.getCdrsJsonCount());
            for (String json : request.getCdrsJsonList())
                cdrs.add(CdrJson.readValue(json, cdr.class));
        } catch (Exception ex) {
            return CdrBatchResult.newBuilder().setError("cdr json parse error: " + ex.getMessage()).build();
        }

        CdrProcessingResult result = _cdrProcessor.ProcessBatch(request.getTenant(), cdrs);
        if (result.Batch() == null)
            return CdrBatchResult.newBuilder()
                    .setCommitted(result.Committed())
                    .setError(result.Error() != null ? result.Error() : "")
                    .build();

        var b = result.Batch();
        return CdrBatchResult.newBuilder()
                .setCommitted(true)
                .setRated(b.Rated().size())
                .setErrored(b.Errored().size())
                .setCdrsWritten(b.CdrsWritten())
                .setChargeablesWritten(b.ChargeablesWritten())
                .setCdrErrorsWritten(b.CdrErrorsWritten())
                .setTotalCharged(b.TotalCharged().doubleValue())
                .build();
    }
}
