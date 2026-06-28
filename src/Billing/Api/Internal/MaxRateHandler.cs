using Billing.Config.TenantConfigSync.Api;
using Billing.Mediation.Model;
using Grpc.Core;
using Telcobright.Billing.V1;
using MaxRateEngine = Billing.Mediation.Rating.MaxRateEngine;
using MedFacts = Billing.Mediation.Rating.CallFacts;
using MedServiceType = Billing.Mediation.Rating.ServiceType;
using TierInput = Billing.Mediation.Rating.TierInput;

namespace Billing.Service.Api.Internal;

/// <summary>
/// Implements <c>GetMaxRatePerMinute</c> (internal-by-convention; <see cref="BillingServiceImpl"/> delegates
/// here). Maps the proto request to engine facts, resolves the entry tenant's ancestor chain into per-tier
/// rater inputs, runs <see cref="MaxRateEngine"/>, and maps the result back to the proto reply.
/// </summary>
public sealed class MaxRateHandler
{
    private readonly MaxRateEngine _engine;
    private readonly ITenantRegistry _registry;
    private readonly ILogger<MaxRateHandler> _log;

    public MaxRateHandler(MaxRateEngine engine, ITenantRegistry registry, ILogger<MaxRateHandler> log)
    {
        _engine = engine;
        _registry = registry;
        _log = log;
    }

    public Task<MaxRateReply> Handle(MaxRateRequest request, ServerCallContext context)
    {
        var facts = new MedFacts(request.Tenant, request.PartnerId, request.CallingNumber,
            request.CalledNumber, request.SourceIp, MapServiceType(request.ServiceType), request.StartEpochMillis);

        var result = _engine.Resolve(facts, BuildChain(facts, request.Levels));

        _log.LogInformation(
            "GetMaxRatePerMinute tenant={Tenant} partner={Partner} {Caller}->{Called} tiers={Tiers} ok={Ok}",
            request.Tenant, request.PartnerId, request.CallingNumber, request.CalledNumber,
            result.Tiers.Count, result.Ok);

        var reply = new MaxRateReply { Ok = result.Ok, RejectReason = result.RejectReason };
        foreach (var (dbName, tier) in result.Tiers)
        {
            var tr = new TierResult
            {
                DbName = tier.DbName,
                PartnerId = tier.PartnerId,
                ServiceGroup = tier.ServiceGroupId,
                RejectReason = tier.RejectReason,
            };
            foreach (var c in tier.Candidates)
                tr.Candidates.Add(new RateCandidate
                {
                    PackageAccountId = c.PackageAccountId,
                    Uom = c.Uom,
                    RatePerMinute = c.RatePerMinute,
                    MaxAmountFirstMinute = c.MaxAmountFirstMinute,
                });
            reply.Tiers.Add(dbName, tr);
        }
        return Task.FromResult(reply);
    }

    /// <summary>Resolve the entry tenant's ancestor chain (leaf→root) and project each tier into a rater
    /// input — the WHOLE chain in one call. The per-tier partner comes from routesphere's <c>levels</c>
    /// (by depth: 0=admin/root … leaf=deepest reseller); absent a level, the leaf falls back to the entry
    /// partner and ancestors to 0.</summary>
    private List<TierInput> BuildChain(MedFacts facts, IEnumerable<Level> levels)
    {
        var levelByDepth = new Dictionary<int, Level>();
        foreach (var lvl in levels) levelByDepth[lvl.Depth] = lvl;

        var chain = _registry.AncestorChain(facts.Tenant);
        var inputs = new List<TierInput>(chain.Count);
        for (var i = 0; i < chain.Count; i++)
        {
            var tenant = chain[i];
            var depth = chain.Count - 1 - i;   // chain[0]=leaf=deepest reseller; chain[last]=root=admin (depth 0)
            var partnerId = levelByDepth.TryGetValue(depth, out var level)
                ? level.PartnerId
                : (i == 0 ? facts.PartnerId : 0);
            var packages = tenant.Context.PartnerIdWisePackageAccounts.TryGetValue((long)partnerId, out var pkgs)
                ? pkgs
                : (IReadOnlyList<PackageAccount>)Array.Empty<PackageAccount>();
            inputs.Add(new TierInput(tenant.DbName, partnerId, tenant.Context.MediationContext, packages, tenant.Context.Partners));
        }
        return inputs;
    }

    private static MedServiceType MapServiceType(ServiceType t) =>
        t == ServiceType.Sms ? MedServiceType.Sms : MedServiceType.Voice;
}
