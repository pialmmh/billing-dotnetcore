package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * One outbox entry: the rated cdr + its customer-leg chargeable (the pair the summary builder
 * consumes). Mirrors the Java side's blob contract.
 */
public record Entry(cdr Cdr, acc_chargeable Customer) {}
