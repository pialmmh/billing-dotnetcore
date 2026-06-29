package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.AssignmentDirection;

/**
 * A service family — the lean port of a legacy {@code IServiceFamily}'s CHARGE role. Given the matched
 * {@link rateassign} and the cdr, it computes the leg's charge + tax and returns the
 * {@link acc_chargeable} (the charge record the summary reads), mutating the relevant cdr fields.
 *
 * <p>SCOPE: this ports the family's RATING MATH only. The legacy families also do accounting/posting — GL
 * {@code postingAccount}, {@code BillingRule}, {@code acc_transaction}, {@code AutoIncrementManager} id,
 * {@code TelcobrightJob} bookkeeping — which is routesphere's mem-ledger domain + the batch scaffolding
 * Option B replaces; those are deferred. The family-internal service-tuple resolution (SF11's own
 * GetServiceTuple) is also deferred — the rate is matched upstream and handed in.
 */
public interface IServiceFamily {
    int Id();

    acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            int maxDecimalPrecision);
}
