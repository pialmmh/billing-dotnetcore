package com.telcobright.billing.api.internal;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.grpc.AssignDirection;
import com.telcobright.billing.grpc.FinalizeRequest;
import com.telcobright.billing.grpc.FinalizeResponse;
import com.telcobright.billing.grpc.Level;
import com.telcobright.billing.grpc.LevelSettlement;
import com.telcobright.billing.mediation.engine.models.cdr;
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
    private final CdrProcessor _cdrProcessor;

    @Inject
    public FinalizeHandler(FinalizeEngine finalize, ITenantRegistry registry, CdrProcessor cdrProcessor) {
        this._finalize = finalize;
        this._registry = registry;
        this._cdrProcessor = cdrProcessor;
    }

    public FinalizeResponse Handle(FinalizeRequest request) {
        com.telcobright.billing.grpc.CallFacts f = request.getFacts();

        // Split the depth-indexed levels by direction: the CUSTOMER partner is the tier's in-partner (charged on
        // the customer leg); a SUPPLIER-direction level supplies the out-partner (the cost leg) — Part 4.
        Map<Integer, Level> customerByDepth = new HashMap<>();
        Map<Integer, Level> supplierByDepth = new HashMap<>();
        for (Level lvl : request.getLevelsList()) {
            if (lvl.getDirection() == AssignDirection.SUPPLIER) supplierByDepth.put(lvl.getDepth(), lvl);
            else customerByDepth.put(lvl.getDepth(), lvl);      // CUSTOMER or UNSPECIFIED
        }
        // The FULL tier (depth 0 / admin) charges the supplier leg; its out-partner is the depth-0 SUPPLIER level.
        int outPartner = supplierByDepth.containsKey(0) ? supplierByDepth.get(0).getPartnerId() : 0;

        String correlationId = (f.getSessionId() == null || f.getSessionId().isEmpty())
                ? f.getSipCallId() : f.getSessionId();
        long startEpoch = f.getStartEpochMillis() > 0 ? f.getStartEpochMillis() : request.getAnswerEpochMillis();
        LocalDateTime startTime = Instant.ofEpochMilli(startEpoch).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime answerTime = request.getAnswerEpochMillis() > 0
                ? Instant.ofEpochMilli(request.getAnswerEpochMillis()).atZone(ZoneOffset.UTC).toLocalDateTime()
                : startTime;

        FinalizeFacts facts = new FinalizeFacts(
                f.getTenant(), f.getCallerNumber(), f.getCalledNumber(), MapServiceType(f.getServiceType()),
                f.getSwitchId(), f.getIncomingRoute(), f.getOutgoingRoute(),
                outPartner,                                  // Part 4: real out-partner (was hardcoded 0)
                answerTime,
                request.getBillsec(), request.getAnswered(), correlationId);

        List<Tenant> chain = _registry.AncestorChain(f.getTenant());
        Map<String, Integer> depthByDbName = new HashMap<>();
        List<FinalizeTierInput> tiers = BuildFinalizeChain(chain, customerByDepth, depthByDbName,
                request.getReservedAmount());
        FinalizeResult result = _finalize.Finalize(facts, tiers);

        // Part 2: PERSIST each applicable tier's cdr + chargeable(s) + summary_affected to its OWN schema,
        // idempotently (per-schema UniqueBillId dedup). Reuses the full write path (CdrProcessor.ProcessBatch),
        // so the supplier leg (Part 4) is persisted too whenever the FULL tier's cdr carries an OutPartnerId.
        PersistOutcome persisted = PersistTiers(chain, depthByDbName, customerByDepth, outPartner,
                f, request, startTime, answerTime, correlationId);

        log.infof("FinalizeAndSummarize tenant=%s session=%s billsec=%d tiers=%d ok=%s total=%s cdrsWritten=%d summariesWritten=%d",
                f.getTenant(), correlationId, request.getBillsec(), result.Settlements().size(),
                result.Success(), result.TotalCharged(), persisted.cdrsWritten(), persisted.summariesWritten());

        FinalizeResponse.Builder response = FinalizeResponse.newBuilder()
                .setSuccess(result.Success())
                .setError(result.Error())
                .setTotalCharged(result.TotalCharged().doubleValue())
                .setCdrId(correlationId)
                .setCdrWritten(persisted.cdrsWritten() > 0)
                .setSummaryWritten(persisted.summariesWritten() > 0);
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
     * MediationContext + Partners from the config cache, with the per-tier CUSTOMER partner/reserved taken from
     * the request's depth-indexed levels (depth 0 = admin/root -> FULL; deeper = reseller -> customer-only). */
    private static List<FinalizeTierInput> BuildFinalizeChain(List<Tenant> chain,
            Map<Integer, Level> customerByDepth, Map<String, Integer> depthByDbName, double reservedAmount) {
        List<FinalizeTierInput> tiers = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            Tenant tenant = chain.get(i);
            int depth = chain.size() - 1 - i;   // chain[0]=leaf=deepest reseller; chain[last]=root=admin (depth 0)
            depthByDbName.put(tenant.DbName, depth);

            Level level = customerByDepth.get(depth);
            int partnerId = level != null ? level.getPartnerId() : 0;
            TierMode mode = depth == 0 ? TierMode.Full : TierMode.CustomerOnly;
            TierReserved reserved = level == null
                    ? null
                    : new TierReserved(level.getPackageAccountId(), "BDT", BigDecimal.valueOf(reservedAmount));

            tiers.add(new FinalizeTierInput(tenant.DbName, partnerId, tenant.Context.MediationContext,
                    tenant.Context.Partners, mode, reserved));
        }
        return tiers;
    }

    /** Part 2: write each applicable tier's cdr + chargeable(s) + summary_affected to its OWN schema via the
     * idempotent batch write path. A tier with no CUSTOMER partner is skipped (nothing to bill there). Each
     * ProcessBatch is per-tenant/per-schema (own connection, own tx, own RateCache), so tiers stay isolated;
     * repeat Finalize calls dedup per schema on {@code UniqueBillId}. */
    private PersistOutcome PersistTiers(List<Tenant> chain, Map<String, Integer> depthByDbName,
            Map<Integer, Level> customerByDepth, int outPartner,
            com.telcobright.billing.grpc.CallFacts f, FinalizeRequest request,
            LocalDateTime startTime, LocalDateTime answerTime, String correlationId) {
        int cdrsWritten = 0, summariesWritten = 0;
        for (Tenant tenant : chain) {
            int depth = depthByDbName.getOrDefault(tenant.DbName, -1);
            Level cust = customerByDepth.get(depth);
            if (cust == null || cust.getPartnerId() <= 0) continue;   // no customer partner for this tier
            int tierOut = depth == 0 ? outPartner : 0;                // supplier leg only at the FULL/root tier
            cdr c = BuildFinalizeCdr(f, request, cust.getPartnerId(), tierOut, startTime, answerTime, correlationId);
            try {
                CdrProcessingResult r = _cdrProcessor.ProcessBatch(tenant.DbName, List.of(c));
                if (!r.Committed() || r.Batch() == null) {
                    log.warnf("finalize persist: tenant=%s session=%s not committed: %s",
                            tenant.DbName, correlationId, r.Error());
                    continue;
                }
                cdrsWritten += r.Batch().CdrsWritten();
                if (!r.Batch().Rated().isEmpty()) summariesWritten++;   // one summary_affected row per rated batch
            } catch (RuntimeException ex) {
                log.errorf(ex, "finalize persist failed: tenant=%s session=%s", tenant.DbName, correlationId);
            }
        }
        return new PersistOutcome(cdrsWritten, summariesWritten);
    }

    /** Build the per-tier cdr from the call facts for the write path (the same shape the Kafka ingest produces).
     * The out-partner is set only for the FULL/root tier so the pipeline emits the supplier leg there (Part 4).
     * {@code UniqueBillId} = the call's correlation id, so each tier's schema bills the leg exactly once. */
    private static cdr BuildFinalizeCdr(com.telcobright.billing.grpc.CallFacts f, FinalizeRequest request,
            int inPartner, int outPartner, LocalDateTime startTime, LocalDateTime answerTime, String correlationId) {
        cdr c = new cdr();
        c.SwitchId = f.getSwitchId();
        c.UniqueBillId = correlationId;
        c.FileName = "grpc:finalize";
        c.InPartnerId = inPartner;
        if (outPartner > 0) c.OutPartnerId = outPartner;
        c.OriginatingCallingNumber = f.getCallerNumber();
        c.TerminatingCallingNumber = f.getCallerNumber();
        c.OriginatingCalledNumber = f.getCalledNumber();
        c.TerminatingCalledNumber = f.getCalledNumber();
        c.StartTime = startTime;
        c.SignalingStartTime = startTime;
        c.AnswerTime = answerTime;
        c.ConnectTime = request.getAnswered() ? answerTime : null;
        c.EndTime = answerTime.plusSeconds(Math.max(0, request.getBillsec()));
        c.DurationSec = BigDecimal.valueOf(request.getBillsec());
        c.ChargingStatus = request.getAnswered() ? 1 : 0;
        c.IncomingRoute = f.getIncomingRoute();
        c.OutgoingRoute = f.getOutgoingRoute();
        return c;
    }

    private record PersistOutcome(int cdrsWritten, int summariesWritten) {
    }

    private static ServiceType MapServiceType(com.telcobright.billing.grpc.ServiceType t) {
        return t == com.telcobright.billing.grpc.ServiceType.SMS ? ServiceType.Sms : ServiceType.Voice;
    }
}
