using System.Text.Json;
using Billing.Config.TenantConfigSync.Api;
using Billing.Config.TenantConfigSync.Model;
using Billing.Data;
using Billing.Mediation.Model;
using Grpc.Core;
using Telcobright.Billing.V1;
using MaxRateEngine = Billing.Mediation.Rating.MaxRateEngine;
using MedFacts = Billing.Mediation.Rating.CallFacts;
using MedServiceType = Billing.Mediation.Rating.ServiceType;
using TierInput = Billing.Mediation.Rating.TierInput;
using MedFinalizeEngine = Billing.Mediation.Rating.FinalizeEngine;
using MedFinalizeFacts = Billing.Mediation.Rating.FinalizeFacts;
using MedFinalizeTier = Billing.Mediation.Rating.FinalizeTierInput;
using MedTierReserved = Billing.Mediation.Rating.TierReserved;
using MedTierMode = Billing.Mediation.Rating.TierMode;

namespace Billing.Service.Services;

/// <summary>
/// gRPC entry point for the rating/CDR/summary service. A THIN adapter: it maps the proto request to
/// engine input, resolves the entry tenant's ancestor chain from the config cache, delegates the
/// multi-tier rating to <see cref="MaxRateEngine"/>, and maps the result back to the proto reply.
/// No business logic here. FinalizeAndSummarize (post-call) lands in a later slice.
/// </summary>
public sealed class BillingServiceImpl : RatingService.RatingServiceBase
{
    private static readonly JsonSerializerOptions CdrJson = new() { PropertyNameCaseInsensitive = true };

    private readonly ITenantRegistry _registry;
    private readonly MaxRateEngine _engine;
    private readonly MedFinalizeEngine _finalize;
    private readonly MySqlConnectionFactory _connections;
    private readonly MySqlCdrBatchRunner _batchRunner;
    private readonly ILogger<BillingServiceImpl> _log;

    public BillingServiceImpl(ITenantRegistry registry, MaxRateEngine engine, MedFinalizeEngine finalize,
        MySqlConnectionFactory connections, MySqlCdrBatchRunner batchRunner, ILogger<BillingServiceImpl> log)
    {
        _registry = registry;
        _engine = engine;
        _finalize = finalize;
        _connections = connections;
        _batchRunner = batchRunner;
        _log = log;
    }

    /// <summary>
    /// Batch CDR processing — the Kafka-fed path, exercised here from a test client. Deserializes the JSON
    /// cdrs into the full <c>cdr</c> POCO, resolves the tenant's MediationContext + Partners, and runs the
    /// SAME pipeline the Kafka consumer will (<see cref="MySqlCdrBatchRunner"/> → <c>CdrProcessor.Process</c>)
    /// — writing cdr + acc_chargeable + summaries (+ cdrerror) into the tenant's schema in ONE transaction.
    /// </summary>
    public override Task<CdrBatchResult> ProcessCdrBatch(CdrBatchRequest request, ServerCallContext context)
    {
        var tenant = _registry.FindByDbName(request.Tenant);
        if (tenant is null)
            return Task.FromResult(new CdrBatchResult { Error = $"unknown tenant '{request.Tenant}'" });
        if (!_connections.IsConfigured)
            return Task.FromResult(new CdrBatchResult { Error = "datasource credentials not configured (set Billing:Db:User / Billing:Db:Password)" });

        List<MediationModel.cdr> cdrs;
        try
        {
            cdrs = new List<MediationModel.cdr>(request.CdrsJson.Count);
            foreach (var json in request.CdrsJson)
                cdrs.Add(JsonSerializer.Deserialize<MediationModel.cdr>(json, CdrJson)!);
        }
        catch (Exception ex)
        {
            return Task.FromResult(new CdrBatchResult { Error = "cdr json parse error: " + ex.Message });
        }

        try
        {
            // one connection to the tenant's own schema; the runner owns the one commit/rollback.
            using var conn = _connections.Open(request.Tenant);
            var r = _batchRunner.Run(conn, tenant.Context.MediationContext, tenant.Context.Partners, cdrs);

            _log.LogInformation(
                "ProcessCdrBatch tenant={Tenant} cdrs={Count} rated={Rated} errored={Errored} charged={Total}",
                request.Tenant, cdrs.Count, r.Rated.Count, r.Errored.Count, r.TotalCharged);

            return Task.FromResult(new CdrBatchResult
            {
                Committed = true,
                Rated = r.Rated.Count,
                Errored = r.Errored.Count,
                CdrsWritten = r.CdrsWritten,
                ChargeablesWritten = r.ChargeablesWritten,
                CdrErrorsWritten = r.CdrErrorsWritten,
                TotalCharged = (double)r.TotalCharged,
            });
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "ProcessCdrBatch tenant={Tenant} rolled back", request.Tenant);
            return Task.FromResult(new CdrBatchResult { Committed = false, Error = ex.Message });
        }
    }

    public override Task<MaxRateReply> GetMaxRatePerMinute(MaxRateRequest request, ServerCallContext context)
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

    public override Task<FinalizeResponse> FinalizeAndSummarize(FinalizeRequest request, ServerCallContext context)
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
}
