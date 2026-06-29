package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

/**
 * A service family — the lean port of a legacy {@code IServiceFamily}'s CHARGE role. Given the matched
 * {@link Rateext} (rate + rate-plan-assignment overlay) and the cdr, it computes the leg's charge + tax and
 * returns the {@link acc_chargeable} (the charge record the summary reads), mutating the relevant cdr fields
 * (legacy A2ZRater end + the family: MatchedPrefix*, *Rate, *PartnerCost, Duration1/2, CountryCode + tax).
 *
 * <p>The {@link MediationContext} carries the rating knobs A2ZRater needs ({@code DicRatePlan},
 * {@code BillingSpans}, {@code MaxDecimalPrecision}) so billing span + RateAmountRoundupDecimal can be read.
 *
 * <p>SCOPE: this ports the family's RATING MATH only. The legacy families also do accounting/posting — GL
 * {@code postingAccount}, {@code BillingRule}, {@code acc_transaction}, {@code AutoIncrementManager} id,
 * {@code TelcobrightJob} bookkeeping — which is the mem-ledger domain + the batch scaffolding; those are
 * deferred. The family-internal service-tuple resolution is also deferred — the rate is matched upstream.
 */
public interface IServiceFamily {
    int Id();

    acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation);
}
