package com.telcobright.billing.api;

import com.telcobright.billing.api.internal.FinalizeHandler;
import com.telcobright.billing.api.internal.MaxRateHandler;
import com.telcobright.billing.api.internal.ProcessCdrBatchHandler;
import com.telcobright.billing.grpc.CdrBatchRequest;
import com.telcobright.billing.grpc.CdrBatchResult;
import com.telcobright.billing.grpc.FinalizeRequest;
import com.telcobright.billing.grpc.FinalizeResponse;
import com.telcobright.billing.grpc.MaxRateReply;
import com.telcobright.billing.grpc.MaxRateRequest;
import com.telcobright.billing.grpc.RatingService;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;

/**
 * The gRPC surface — a thin wrapper. Each RPC delegates to one internal handler; all logic lives there (and
 * in the beans/engine beneath). The blocking work runs on the worker pool so the gRPC event loop is never
 * blocked. Port of the .NET {@code BillingServiceImpl} (which delegated to the same three handlers).
 */
@GrpcService
public class BillingServiceImpl implements RatingService {

    private final ProcessCdrBatchHandler _processCdrBatch;
    private final MaxRateHandler _maxRate;
    private final FinalizeHandler _finalize;

    @Inject
    public BillingServiceImpl(ProcessCdrBatchHandler processCdrBatch, MaxRateHandler maxRate, FinalizeHandler finalize) {
        this._processCdrBatch = processCdrBatch;
        this._maxRate = maxRate;
        this._finalize = finalize;
    }

    @Override
    public Uni<MaxRateReply> getMaxRatePerMinute(MaxRateRequest request) {
        return Uni.createFrom().item(() -> _maxRate.Handle(request))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<FinalizeResponse> finalizeAndSummarize(FinalizeRequest request) {
        return Uni.createFrom().item(() -> _finalize.Handle(request))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<CdrBatchResult> processCdrBatch(CdrBatchRequest request) {
        return Uni.createFrom().item(() -> _processCdrBatch.Handle(request))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
