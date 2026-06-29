package com.telcobright.billing.api.internal;

import com.telcobright.billing.grpc.Level;
import com.telcobright.billing.grpc.MaxRateReply;
import com.telcobright.billing.grpc.MaxRateRequest;
import com.telcobright.billing.grpc.TierResult;
import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.rating.CallFacts;
import com.telcobright.billing.mediation.rating.MaxRateEngine;
import com.telcobright.billing.mediation.rating.MaxRateResult;
import com.telcobright.billing.mediation.rating.RateCandidate;
import com.telcobright.billing.mediation.rating.ServiceType;
import com.telcobright.billing.mediation.rating.TierInput;
import com.telcobright.billing.mediation.rating.TierRating;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@code GetMaxRatePerMinute} (internal-by-convention; {@code BillingServiceImpl} delegates here).
 * Maps the proto request to engine facts, resolves the entry tenant's ancestor chain into per-tier rater
 * inputs, runs {@link MaxRateEngine}, and maps the result back to the proto reply.
 */
@Singleton
public class MaxRateHandler {
    private static final Logger log = Logger.getLogger(MaxRateHandler.class);

    private final MaxRateEngine _engine;
    private final ITenantRegistry _registry;

    @Inject
    public MaxRateHandler(MaxRateEngine engine, ITenantRegistry registry) {
        this._engine = engine;
        this._registry = registry;
    }

    public MaxRateReply Handle(MaxRateRequest request) {
        CallFacts facts = new CallFacts(request.getTenant(), request.getPartnerId(), request.getCallingNumber(),
                request.getCalledNumber(), request.getSourceIp(), MapServiceType(request.getServiceType()),
                request.getStartEpochMillis());

        MaxRateResult result = _engine.Resolve(facts, BuildChain(facts, request.getLevelsList()));

        log.infof("GetMaxRatePerMinute tenant=%s partner=%d %s->%s tiers=%d ok=%s",
                request.getTenant(), request.getPartnerId(), request.getCallingNumber(), request.getCalledNumber(),
                result.Tiers().size(), result.Ok());

        MaxRateReply.Builder reply = MaxRateReply.newBuilder().setOk(result.Ok()).setRejectReason(result.RejectReason());
        for (Map.Entry<String, TierRating> e : result.Tiers().entrySet()) {
            TierRating tier = e.getValue();
            TierResult.Builder tr = TierResult.newBuilder()
                    .setDbName(tier.DbName())
                    .setPartnerId(tier.PartnerId())
                    .setServiceGroup(tier.ServiceGroupId())
                    .setRejectReason(tier.RejectReason());
            for (RateCandidate c : tier.Candidates())
                tr.addCandidates(com.telcobright.billing.grpc.RateCandidate.newBuilder()
                        .setPackageAccountId(c.PackageAccountId())
                        .setUom(c.Uom())
                        .setRatePerMinute(c.RatePerMinute())
                        .setMaxAmountFirstMinute(c.MaxAmountFirstMinute())
                        .build());
            reply.putTiers(e.getKey(), tr.build());
        }
        return reply.build();
    }

    /** Resolve the entry tenant's ancestor chain (leaf->root) and project each tier into a rater input — the
     * WHOLE chain in one call. The per-tier partner comes from routesphere's {@code levels} (by depth:
     * 0=admin/root ... leaf=deepest reseller); absent a level, the leaf falls back to the entry partner and
     * ancestors to 0. */
    private List<TierInput> BuildChain(CallFacts facts, List<Level> levels) {
        Map<Integer, Level> levelByDepth = new HashMap<>();
        for (Level lvl : levels) levelByDepth.put(lvl.getDepth(), lvl);

        List<Tenant> chain = _registry.AncestorChain(facts.Tenant());
        List<TierInput> inputs = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            Tenant tenant = chain.get(i);
            int depth = chain.size() - 1 - i;   // chain[0]=leaf=deepest reseller; chain[last]=root=admin (depth 0)
            Level level = levelByDepth.get(depth);
            int partnerId = level != null ? level.getPartnerId() : (i == 0 ? facts.PartnerId() : 0);
            List<PackageAccount> packages =
                    tenant.Context.PartnerIdWisePackageAccounts.getOrDefault((long) partnerId, List.of());
            inputs.add(new TierInput(tenant.DbName, partnerId, tenant.Context.MediationContext, packages,
                    tenant.Context.Partners));
        }
        return inputs;
    }

    private static ServiceType MapServiceType(com.telcobright.billing.grpc.ServiceType t) {
        return t == com.telcobright.billing.grpc.ServiceType.SMS ? ServiceType.Sms : ServiceType.Voice;
    }
}
