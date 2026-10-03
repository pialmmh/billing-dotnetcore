package com.telcobright.billing.mediation.servicegroups;

/**
 * SG 30 — "An ad view" (owner's ruling 2026-10-02: an ad is a Call; routesphere
 * {@code docs/architecture/ad-is-a-call.md} §4, §5).
 *
 * <p><b>It is not a detector, on purpose.</b> Every other service group is DETECTED from the call's facts (the
 * in-partner's type, the dialed number). Group 30 is STATED by the producer on the wire ({@code serviceGroup: 30})
 * and taken as given: an advertiser is a partner like any other, and its partner type must never turn its ad into
 * a domestic call. So a record that carries 30 is 30, and it is never handed to
 * {@link ServiceGroupDetection} — which would first reset the group to 0 and then let SG10 / SG11 / SG15 claim it.
 *
 * <p>It is also PRE-RATED: the switch's settle step has already charged the ledger. Billing looks no rate up for
 * it; its service family ({@code SfPreRated}) builds the chargeable from the record's own settled amounts.
 */
public final class SgAdView {
    private SgAdView() {}

    public static final int Id = 30;
    public static final String RuleName = "Ad View [pre-rated]";

    /** True when the wire STATES this group. Absent, 0, or any other number is not a statement of it. */
    public static boolean IsStatedBy(Integer wireServiceGroup) {
        return wireServiceGroup != null && wireServiceGroup == Id;
    }

    /** True for a cdr that is an ad view (its group was stated, so it is already stamped). */
    public static boolean Is(int serviceGroup) {
        return serviceGroup == Id;
    }
}
