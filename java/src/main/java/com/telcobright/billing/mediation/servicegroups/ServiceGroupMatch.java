package com.telcobright.billing.mediation.servicegroups;

/**
 * The detected service group for a call plus the normalized chargeable number that the rate
 * lookup (longest-prefix over the resolved rate-plan tuples' rateassigns) consumes.
 */
public record ServiceGroupMatch(int ServiceGroupId, String RuleName, String NormalizedNumber) {
}
