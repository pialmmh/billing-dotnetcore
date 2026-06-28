using Billing.Service.Api.Internal;
using Grpc.Core;
using Telcobright.Billing.V1;

namespace Billing.Service.Api;

/// <summary>
/// gRPC entry point for the rating / CDR / summary service — a THIN surface. Each RPC delegates to its
/// handler in <c>Billing.Service.Api.Internal</c>; there is NO logic here. These three methods ARE the
/// intent of the service:
/// <list type="bullet">
/// <item><see cref="ProcessCdrBatch"/> — rate + write a batch of cdrs for one tenant.</item>
/// <item><see cref="GetMaxRatePerMinute"/> — pre-call: quote the max rate per tier of the tenant chain.</item>
/// <item><see cref="FinalizeAndSummarize"/> — post-call: settle the call per level.</item>
/// </list>
/// </summary>
public sealed class BillingServiceImpl : RatingService.RatingServiceBase
{
    private readonly ProcessCdrBatchHandler _processCdrBatch;
    private readonly MaxRateHandler _maxRate;
    private readonly FinalizeHandler _finalize;

    public BillingServiceImpl(ProcessCdrBatchHandler processCdrBatch, MaxRateHandler maxRate, FinalizeHandler finalize)
    {
        _processCdrBatch = processCdrBatch;
        _maxRate = maxRate;
        _finalize = finalize;
    }

    public override Task<CdrBatchResult> ProcessCdrBatch(CdrBatchRequest request, ServerCallContext context)
        => _processCdrBatch.Handle(request, context);

    public override Task<MaxRateReply> GetMaxRatePerMinute(MaxRateRequest request, ServerCallContext context)
        => _maxRate.Handle(request, context);

    public override Task<FinalizeResponse> FinalizeAndSummarize(FinalizeRequest request, ServerCallContext context)
        => _finalize.Handle(request, context);
}
