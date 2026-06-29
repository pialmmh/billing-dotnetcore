package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;

import java.util.List;
import java.util.Map;

/**
 * Everything the rater needs for ONE tier, extracted from that tier's config snapshot by the
 * caller (so the rater stays free of the tenant-tree / config types). The leaf carries the entry
 * partner; ancestors carry the partner that owns them once the partner hierarchy is wired.
 */
public record TierInput(
        String DbName,
        int PartnerId,
        MediationContext Mediation,
        List<PackageAccount> Packages,
        Map<Integer, Partner> Partners) {
}
