// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

/**
 * A prepaid package account. Live balance lives in routesphere mem-ledger; this carries only the config
 * fields dotnet ranks eligibility by.
 */
public record PackageAccount(
        long Id,
        long IdPartner,
        String Uom,
        Integer OnSelectPriority,
        String Status
) {
}
