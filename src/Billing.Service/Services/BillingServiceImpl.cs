using Billing.Config.TenantConfigSync.Api;
using Billing.Mediation.Model;
using Grpc.Core;
using Telcobright.Billing.V1;
using MaxRateEngine = Billing.Mediation.Rating.MaxRateEngine;
using MedFacts = Billing.Mediation.Rating.CallFacts;
using MedServiceType = Billing.Mediation.Rating.ServiceType;
using TierInput = Billing.Mediation.Rating.TierInput;

namespace Billing.Service.Services;

/// <summary>
/// gRPC entry point for the rating/CDR/summary service. A THIN adapter: it maps the proto request to
/// engine input, resolves the entry tenant's ancestor chain from the config cache, delegates the
/// multi-tier rating to <see cref="MaxRateEngine"/>, and maps the result back to the proto reply.
/// No business logic here. FinalizeAndSummarize (post-call) lands in a later slice.
/// </summary>
public sealed class BillingServiceImpl : RatingService.RatingServiceBase
{
    private readonly ITenantRegistry _registry;
    private readonly MaxRateEngine _engine;
    private readonly ILogger<BillingServiceImpl> _log;

    public BillingServiceImpl(ITenantRegistry registry, MaxRateEngine engine, ILogger<BillingServiceImpl> log)
    {
        _registry = registry;
        _engine = engine;
        _log = log;
    }

    public override Task<MaxRateReply> GetMaxRatePerMinute(MaxRateRequest request, ServerCallContext context)
    {
        var facts = new MedFacts(request.Tenant, request.PartnerId, request.CallingNumber,
            request.CalledNumber, request.SourceIp, MapServiceType(request.ServiceType), request.StartEpochMillis);

        var result = _engine.Resolve(facts, BuildChain(facts));

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

    /// <summary>Resolve the entry tenant's ancestor chain (leaf→root) and project each tier into a
    /// rater input. The leaf carries the entry partner; ancestor partner ids come with the partner
    /// hierarchy (not yet wired) — 0 for now.</summary>
    private List<TierInput> BuildChain(MedFacts facts)
    {
        var chain = _registry.AncestorChain(facts.Tenant);
        var inputs = new List<TierInput>(chain.Count);
        for (var i = 0; i < chain.Count; i++)
        {
            var tenant = chain[i];
            var partnerId = i == 0 ? facts.PartnerId : 0;
            var packages = tenant.Context.PartnerIdWisePackageAccounts.TryGetValue((long)partnerId, out var pkgs)
                ? pkgs
                : (IReadOnlyList<PackageAccount>)Array.Empty<PackageAccount>();
            inputs.Add(new TierInput(tenant.DbName, partnerId, tenant.Context.MediationContext, packages));
        }
        return inputs;
    }

    private static MedServiceType MapServiceType(ServiceType t) =>
        t == ServiceType.Sms ? MedServiceType.Sms : MedServiceType.Voice;

    public override Task<FinalizeResponse> FinalizeAndSummarize(
        FinalizeRequest request, ServerCallContext context)
    {
        var f = request.Facts;
        _log.LogInformation(
            "FinalizeAndSummarize tenant={Tenant} session={Session} answered={Answered} billsec={Billsec}",
            f?.Tenant, f?.SessionId, request.Answered, request.Billsec);

        throw new RpcException(new Status(StatusCode.Unimplemented,
            "FinalizeAndSummarize: post-call rating/CDR/summary pending (next slice)"));
    }
}
