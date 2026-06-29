package com.telcobright.billing.api.internal;

import com.telcobright.billing.grpc.FinalizeRequest;
import com.telcobright.billing.grpc.FinalizeResponse;
import com.telcobright.billing.grpc.Level;
import com.telcobright.billing.grpc.LevelSettlement;
import com.telcobright.billing.mediation.rating.FinalizeEngine;
import com.telcobright.billing.mediation.rating.FinalizeFacts;
import com.telcobright.billing.mediation.rating.FinalizeResult;
import com.telcobright.billing.mediation.rating.FinalizeTierInput;
import com.telcobright.billing.mediation.rating.ServiceType;
import com.telcobright.billing.mediation.rating.TierMode;
import com.telcobright.billing.mediation.rating.TierReserved;
import com.telcobright.billing.mediation.rating.TierSettlement;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@code FinalizeAndSummarize} (internal-by-convention; {@code BillingServiceImpl} delegates here).
 * Maps the proto facts + depth-indexed levels onto the entry tenant's ancestor chain, runs
 * {@link FinalizeEngine} for the per-level settlement, and maps the result back to the proto reply.
 * Persistence (the cdr/summary write) is a later slice — see {@code CdrWritten}/{@code SummaryWritten} = false.
 */
@Singleton
public class FinalizeHandler {
    private static final Logger log = Logger.getLogger(FinalizeHandler.class);

    private final FinalizeEngine _finalize;
    private final ITenantRegistry _registry;

    @Inject
    public FinalizeHandler(FinalizeEngine finalize, ITenantRegistry registry) {
        this._finalize = finalize;
        this._registry = registry;
    }

    public FinalizeResponse Handle(FinalizeRequest request) {
        com.telcobright.billing.grpc.CallFacts f = request.getFacts();
        FinalizeFacts facts = new FinalizeFacts(
                f.getTenant(), f.getCallerNumber(), f.getCalledNumber(), MapServiceType(f.getServiceType()),
                f.getSwitchId(), f.getIncomingRoute(), f.getOutgoingRoute(),
                0,   // current proto carries no out-partner — the supplier leg via gRPC awaits the reshape
                Instant.ofEpochMilli(request.getAnswerEpochMillis()).atZone(ZoneOffset.UTC).toLocalDateTime(),
                request.getBillsec(), request.getAnswered(),
                (f.getSessionId() == null || f.getSessionId().isEmpty()) ? f.getSipCallId() : f.getSessionId());

        List<Tenant> chain = _registry.AncestorChain(f.getTenant());
        Map<String, Integer> depthByDbName = new HashMap<>();
        List<FinalizeTierInput> tiers = BuildFinalizeChain(chain, request, depthByDbName);
        FinalizeResult result = _finalize.Finalize(facts, tiers);

        log.infof("FinalizeAndSummarize tenant=%s session=%s billsec=%d tiers=%d ok=%s total=%s",
                f.getTenant(), f.getSessionId(), request.getBillsec(), result.Settlements().size(),
                result.Success(), result.TotalCharged());

        FinalizeResponse.Builder response = FinalizeResponse.newBuilder()
                .setSuccess(result.Success())
                .setError(result.Error())
                .setTotalCharged(result.TotalCharged().doubleValue())
                .setCdrWritten(false)        // persistence (the single-connection cdr/summary write) is a later slice
                .setSummaryWritten(false);
        for (Map.Entry<String, TierSettlement> e : result.Settlements().entrySet()) {
            TierSettlement s = e.getValue();
            response.addSettlements(LevelSettlement.newBuilder()
                    .setDepth(depthByDbName.getOrDefault(e.getKey(), 0))
                    .setPartnerId(s.PartnerId())
                    .setUom(s.Uom())
                    .setChargedAmount(s.Charged().doubleValue())
                    .setPackageAmount(s.PackageAmount().doubleValue())
                    .setInPartnerCost(s.InPartnerCost().doubleValue())
                    .setMatchedPrefix(s.MatchedPrefix())
                    .setServiceGroupId(s.ServiceGroupId())
                    .setServiceFamilyId(s.ServiceFamilyId())
                    .build());
        }
        return response.build();
    }

    /** Map the entry tenant's ancestor chain (leaf->root) to per-tier finalize inputs: each tier's dbName +
     * MediationContext + Partners from the config cache, with the per-tier partner/reserved taken from the
     * request's depth-indexed levels (depth 0 = admin/root -> FULL; deeper = reseller -> customer-only). */
    private static List<FinalizeTierInput> BuildFinalizeChain(List<Tenant> chain, FinalizeRequest request,
            Map<String, Integer> depthByDbName) {
        Map<Integer, Level> levelByDepth = new HashMap<>();
        for (Level lvl : request.getLevelsList()) levelByDepth.put(lvl.getDepth(), lvl);

        List<FinalizeTierInput> tiers = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            Tenant tenant = chain.get(i);
            int depth = chain.size() - 1 - i;   // chain[0]=leaf=deepest reseller; chain[last]=root=admin (depth 0)
            depthByDbName.put(tenant.DbName, depth);

            Level level = levelByDepth.get(depth);
            int partnerId = level != null ? level.getPartnerId() : 0;
            TierMode mode = depth == 0 ? TierMode.Full : TierMode.CustomerOnly;
            TierReserved reserved = level == null
                    ? null
                    : new TierReserved(level.getPackageAccountId(), "BDT", BigDecimal.valueOf(request.getReservedAmount()));

            tiers.add(new FinalizeTierInput(tenant.DbName, partnerId, tenant.Context.MediationContext,
                    tenant.Context.Partners, mode, reserved));
        }
        return tiers;
    }

    private static ServiceType MapServiceType(com.telcobright.billing.grpc.ServiceType t) {
        return t == com.telcobright.billing.grpc.ServiceType.SMS ? ServiceType.Sms : ServiceType.Voice;
    }
}
