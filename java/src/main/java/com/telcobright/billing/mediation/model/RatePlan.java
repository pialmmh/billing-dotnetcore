// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

import java.util.List;

/**
 * A rate plan groups today's rates that apply to a set of partners.
 *
 * <p>FAITHFUL-PORT NOTE: positional Java record (components in C# declaration order). The C# default
 * {@code PartnerIds = []} is preserved via the compact constructor (null normalises to an empty list).</p>
 */
public record RatePlan(
        int Id,
        String Name,
        List<Integer> PartnerIds
) {
    public RatePlan {
        if (PartnerIds == null) PartnerIds = List.of();
    }
}
