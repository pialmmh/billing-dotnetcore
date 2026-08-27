package com.telcobright.billing.api.internal;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.grpc.ReprocessRequest;
import com.telcobright.billing.grpc.ReprocessResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Adapts the gRPC {@code ReprocessErrors} RPC to {@link CdrProcessor#ReprocessErrors}: read a tenant's
 * cdrerror rows, re-rate them, and atomically move the recovered ones into {@code cdr}. The atomicity +
 * idempotency live in the bean/runner; this is just the proto boundary.
 */
@Singleton
public class ReprocessErrorsHandler {
    private final CdrProcessor _cdrProcessor;

    @Inject
    public ReprocessErrorsHandler(CdrProcessor cdrProcessor) {
        this._cdrProcessor = cdrProcessor;
    }

    public ReprocessResult Handle(ReprocessRequest request) {
        int limit = request.getLimit() > 0 ? request.getLimit() : 1000;
        CdrProcessingResult result = _cdrProcessor.ReprocessErrors(
                request.getTenant(), request.getErrorCode(), request.getOnlySuccessful(), limit);

        if (result.Batch() == null)
            return ReprocessResult.newBuilder()
                    .setCommitted(result.Committed())
                    .setError(result.Error() != null ? result.Error() : "")
                    .build();

        var b = result.Batch();
        int recovered = b.CdrsWritten();
        int stillErrored = b.CdrErrorsWritten();
        return ReprocessResult.newBuilder()
                .setCommitted(true)
                .setRead(recovered + stillErrored)
                .setRecovered(recovered)
                .setStillErrored(stillErrored)
                .setChargeablesWritten(b.ChargeablesWritten())
                .setTotalCharged(b.TotalCharged().doubleValue())
                .build();
    }
}
