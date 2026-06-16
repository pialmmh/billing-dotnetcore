using Grpc.Core;
using Telcobright.Billing.V1;

namespace Billing.Service.Services;

/// <summary>
/// gRPC entry point for the rating/CDR/summary service.
///
/// This is a THIN adapter. Its only jobs are:
///   1. map proto request &lt;-&gt; engine input,
///   2. resolve the tenant's MediationContext (from the per-tenant config cache),
///   3. delegate to the rating/CDR/summary engine (Billing.Mediation), and
///   4. map the engine result back to the proto response.
///
/// No business logic lives here. Engine wiring lands in the next slice (voice / admin-only);
/// for now the handlers validate input and return Unimplemented so the contract is callable
/// end-to-end from the routesphere side while the engine is ported.
/// </summary>
public sealed class BillingServiceImpl : RatingService.RatingServiceBase
{
    private readonly ILogger<BillingServiceImpl> _log;

    public BillingServiceImpl(ILogger<BillingServiceImpl> log) => _log = log;

    public override Task<FirstUnitRateResponse> GetFirstUnitRate(
        FirstUnitRateRequest request, ServerCallContext context)
    {
        var f = request.Facts;
        _log.LogInformation(
            "GetFirstUnitRate tenant={Tenant} kind={Kind} {Caller}->{Called} levels={Levels}",
            f?.Tenant, f?.Kind, f?.CallerNumber, f?.CalledNumber, request.Levels.Count);

        throw new RpcException(new Status(StatusCode.Unimplemented,
            "GetFirstUnitRate: rating engine wiring pending (voice/admin slice)"));
    }

    public override Task<FinalizeResponse> FinalizeAndSummarize(
        FinalizeRequest request, ServerCallContext context)
    {
        var f = request.Facts;
        _log.LogInformation(
            "FinalizeAndSummarize tenant={Tenant} session={Session} answered={Answered} billsec={Billsec}",
            f?.Tenant, f?.SessionId, request.Answered, request.Billsec);

        throw new RpcException(new Status(StatusCode.Unimplemented,
            "FinalizeAndSummarize: rating engine wiring pending (voice/admin slice)"));
    }
}
