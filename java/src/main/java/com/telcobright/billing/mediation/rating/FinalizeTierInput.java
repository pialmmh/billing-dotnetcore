package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;

/**
 * Everything the finalize engine needs for ONE tier, extracted from that tier's config snapshot
 * by the caller (so the engine stays free of the tenant-tree/config types). {@code Partners} backs
 * service-group detection; {@code Reserved} is what routesphere held (package vs cash + amount to
 * reconcile).
 */
public record FinalizeTierInput(
        String DbName,
        int PartnerId,
        MediationContext Mediation,
        Map<Integer, Partner> Partners,
        TierMode Mode,
        TierReserved Reserved) {
}
