package com.telcobright.billing.mediation.rating;

/**
 * Per-tier charge mode (the architect's locked contract): the admin/operator tier settles the
 * FULL chain (customer + supplier + families); a reseller tier settles the CUSTOMER leg only.
 */
public enum TierMode { CustomerOnly, Full }
