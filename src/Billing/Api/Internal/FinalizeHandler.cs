using Billing.Config.TenantConfigSync.Api;
using Billing.Config.TenantConfigSync.Model;
using Grpc.Core;
using Telcobright.Billing.V1;
using MedServiceType = Billing.Mediation.Rating.ServiceType;
using MedFinalizeEngine = Billing.Mediation.Rating.FinalizeEngine;
using MedFinalizeFacts = Billing.Mediation.Rating.FinalizeFacts;
using MedFinalizeTier = Billing.Mediation.Rating.FinalizeTierInput;
using MedTierReserved = Billing.Mediation.Rating.TierReserved;
using MedTierMode = Billing.Mediation.Rating.TierMode;

namespace Billing.Service.Api.Internal;

/// <summary>
/// Implements <c>FinalizeAndSummarize</c> (internal-by-convention; <see cref="BillingServiceImpl"/> delegates
/// here). Maps the proto facts + depth-indexed levels onto the entry tenant's ancestor chain, runs
/// <see cref="MedFinalizeEngine"/> for the per-level settlement, and maps the result back to the proto reply.
/// Persistence (the cdr/summary write) is a later slice — see <c>CdrWritten</c>/<c>SummaryWritten</c> = false.
/// </summary>
public sealed class FinalizeHandler
{
    private readonly MedFinalizeEngine _finalize;
    private readonly ITenantRegistry _registry;
    private readonly ILogger<FinalizeHandler> _log;

    public FinalizeHandler(MedFinalizeEngine finalize, ITenantRegistry registry, ILogger<FinalizeHandler> log)
    {
        _finalize = finalize;
        _registry = registry;
        _log = log;
    }

    public Task<FinalizeResponse> Handle(FinalizeRequest request, ServerCallContext context)
    {
        var f = request.Facts;
        var facts = new MedFinalizeFacts(
            f.Tenant, f.CallerNumber, f.CalledNumber, MapServiceType(f.ServiceType),
            f.SwitchId, f.IncomingRoute, f.OutgoingRoute,
            OutPartnerId: 0,   // current proto carries no out-partner — the supplier leg via gRPC awaits the reshape
            AnswerTime: DateTimeOffset.FromUnixTimeMilliseconds(request.AnswerEpochMillis).UtcDateTime,
            Billsec: request.Billsec, Answered: request.Answered,
            UniqueId: string.IsNullOrEmpty(f.SessionId) ? f.SipCallId : f.SessionId);

        var chain = _registry.AncestorChain(f.Tenant);
        var (tiers, depthByDbName) = BuildFinalizeChain(chain, request);
        var result = _finalize.Finalize(facts, tiers);

        _log.LogInformation(
            "FinalizeAndSummarize tenant={Tenant} session={Session} billsec={Billsec} tiers={Tiers} ok={Ok} total={Total}",
            f.Tenant, f.SessionId, request.Billsec, result.Settlements.Count, result.Success, result.TotalCharged);

        var response = new FinalizeResponse
        {
            Success = result.Success,
            Error = result.Error,
            TotalCharged = (double)result.TotalCharged,
            CdrWritten = false,        // persistence (the single-connection cdr/summary write) is a later slice
            SummaryWritten = false,
        };
        foreach (var (dbName, s) in result.Settlements)
        {
            response.Settlements.Add(new LevelSettlement
            {
                Depth = depthByDbName.GetValueOrDefault(dbName, 0),
                PartnerId = s.PartnerId,
                Uom = s.Uom,
                ChargedAmount = (double)s.Charged,
                PackageAmount = (double)s.PackageAmount,
                InPartnerCost = (double)s.InPartnerCost,
                MatchedPrefix = s.MatchedPrefix,
                ServiceGroupId = s.ServiceGroupId,
                ServiceFamilyId = s.ServiceFamilyId,
            });
        }
        return Task.FromResult(response);
    }

    /// <summary>Map the entry tenant's ancestor chain (leaf→root) to per-tier finalize inputs: each tier's
    /// dbName + MediationContext + Partners from the config cache, with the per-tier partner/reserved taken
    /// from the request's depth-indexed levels (depth 0 = admin/root → FULL; deeper = reseller →
    /// customer-only). The depth↔chain alignment and the lean per-tier reserved are placeholders until the
    /// proto reshapes to the dbName-keyed map (architect).</summary>
    private static (List<MedFinalizeTier> Tiers, Dictionary<string, int> DepthByDbName) BuildFinalizeChain(
        IReadOnlyList<Tenant> chain, FinalizeRequest request)
    {
        var levelByDepth = new Dictionary<int, Level>();
        foreach (var lvl in request.Levels) levelByDepth[lvl.Depth] = lvl;

        var tiers = new List<MedFinalizeTier>(chain.Count);
        var depthByDbName = new Dictionary<string, int>(chain.Count);
        for (var i = 0; i < chain.Count; i++)
        {
            var tenant = chain[i];
            var depth = chain.Count - 1 - i;   // chain[0]=leaf=deepest reseller; chain[last]=root=admin (depth 0)
            depthByDbName[tenant.DbName] = depth;

            levelByDepth.TryGetValue(depth, out var level);
            var partnerId = level?.PartnerId ?? 0;
            var mode = depth == 0 ? MedTierMode.Full : MedTierMode.CustomerOnly;
            MedTierReserved? reserved = level is null
                ? null
                : new MedTierReserved(level.PackageAccountId, "BDT", (decimal)request.ReservedAmount);

            tiers.Add(new MedFinalizeTier(tenant.DbName, partnerId, tenant.Context.MediationContext,
                tenant.Context.Partners, mode, reserved));
        }
        return (tiers, depthByDbName);
    }

    private static MedServiceType MapServiceType(ServiceType t) =>
        t == ServiceType.Sms ? MedServiceType.Sms : MedServiceType.Voice;
}
