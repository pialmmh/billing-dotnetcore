// Ported from legacy Mediation/Context/RatingConfig.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.context;

/**
 * One service-group DETECTION rule: a predicate over the call (partnerType + called-prefix, optionally
 * switch/route) -> a category. Ordered by priority, first match wins (mirrors the C# Sg* logic). Each row
 * is one rule, so it maps to one small config-manager table.
 *
 * <p>FAITHFUL-PORT NOTE: positional Java record (components in C# declaration order). The C# default
 * {@code Name = ""} is preserved via the compact constructor (null normalises to "").</p>
 */
public record ServiceGroupRule(
        int Id,
        String Name,
        Integer PartnerType,
        String CalledPrefix,
        Integer SwitchId,
        int CategoryId,
        int Priority
) {
    public ServiceGroupRule {
        if (Name == null) Name = "";
    }
}
