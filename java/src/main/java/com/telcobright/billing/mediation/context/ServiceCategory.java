// Ported from legacy Mediation/Context/RatingConfig.cs (one of several types the .cs declared; split per
// the one-type-per-file rule). Rating-side configuration served by config-manager folded inside DynamicContext.
package com.telcobright.billing.mediation.context;

/**
 * EnumServiceCategory — the cross-system service-group id namespace (config-manager table). {@code category}
 * is the ONE id shared across routesphere and dotnet — it travels in rating results and joins detection,
 * rates, and packages.
 *
 * <p>FAITHFUL-PORT NOTE: positional Java record (components in C# declaration order). The C# default
 * {@code Type = ""} is preserved via the compact constructor (null normalises to "").</p>
 */
public record ServiceCategory(
        int Id,
        String Type
) {
    public ServiceCategory {
        if (Type == null) Type = "";
    }
}
