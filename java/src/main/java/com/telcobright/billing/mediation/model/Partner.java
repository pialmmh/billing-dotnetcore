// Ported from legacy Mediation/Model/Entities.cs (one of several records the .cs declared; split per the
// one-type-per-file rule). Minimal rating-side domain entity, deserialized from config-manager's payload.
package com.telcobright.billing.mediation.model;

/**
 * A partner: operator, reseller, customer, or supplier. Each reseller owns a DB.
 *
 * <p>FAITHFUL-PORT NOTE: the C# {@code sealed record} uses {@code { get; init; }} object-initializer
 * construction; ported to a positional Java record (components in C# declaration order). The C# default
 * {@code PartnerName = ""} is preserved via the compact constructor (null normalises to "").</p>
 */
public record Partner(
        int IdPartner,
        String PartnerName,
        /** config-manager serves partnerType as a number (EnumPartnerType id). */
        Integer PartnerType
) {
    public Partner {
        if (PartnerName == null) PartnerName = "";
    }
}
