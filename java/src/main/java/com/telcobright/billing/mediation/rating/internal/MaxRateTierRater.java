package com.telcobright.billing.mediation.rating.internal;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.rating.CallFacts;
import com.telcobright.billing.mediation.rating.ITierRater;
import com.telcobright.billing.mediation.rating.RateCandidate;
import com.telcobright.billing.mediation.rating.TierInput;
import com.telcobright.billing.mediation.rating.TierRating;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The real per-tier rater for {@code GetMaxRatePerMinute} (admission): detect the service group from the
 * tier's {@code MediationContext} + the called number, match the CUSTOMER rate over the per-day RateCache
 * (reusing {@code BasicCharge.MatchCustomerRate}), and return the ranked candidates — a CASH candidate
 * carrying the per-minute rate + the first-minute max, plus the tier's eligible PACKAGE accounts (consumed
 * at 1 unit / minute). Rejects when no service group is detected, or when neither a rate nor a package is
 * available. routesphere reserves the first FUNDED candidate per tier.
 */
public final class MaxRateTierRater implements ITierRater {
    private final BasicCharge _basicCharge;

    public MaxRateTierRater(BasicCharge basicCharge) {
        this._basicCharge = basicCharge;
    }

    @Override
    public TierRating RateTier(CallFacts facts, TierInput tier) {
        var start = LocalDateTime.ofInstant(Instant.ofEpochMilli(facts.StartEpochMillis()), ZoneOffset.UTC);
        var thisCdr = new cdr();
        thisCdr.InPartnerId = tier.PartnerId();
        thisCdr.OriginatingCallingNumber = facts.CallingNumber();
        // ONE dialed number in the live-call facts -> both called-number fields (the customer-direction rate
        // matches on OriginatingCalledNumber, legacy A2ZRater).
        thisCdr.OriginatingCalledNumber = facts.CalledNumber();
        thisCdr.TerminatingCalledNumber = facts.CalledNumber();
        thisCdr.StartTime = start;
        thisCdr.AnswerTime = start;
        thisCdr.DurationSec = BigDecimal.valueOf(60);

        var matched = _basicCharge.MatchCustomerRate(thisCdr, tier.Mediation(), tier.Partners());
        int serviceGroupId = matched.ServiceGroupId();
        Rateext rate = matched.Rate();
        if (serviceGroupId == 0)
            return new TierRating(tier.DbName(), tier.PartnerId(), 0, "service group not detected", List.of());

        var candidates = new ArrayList<RateCandidate>(tier.Packages().size() + 1);

        // package candidates — consumed at 1 unit / minute (the cash rate doesn't apply to package units).
        for (var p : tier.Packages())
            candidates.add(new RateCandidate(p.Id(), p.Uom() != null ? p.Uom() : "", 0d, 1d));

        // cash candidate — the matched per-minute rate + the first-minute max (faithful A2Z over the first 60s).
        if (rate != null) {
            var med = tier.Mediation();
            var firstMinute = A2ZRater.Rate(rate, BigDecimal.valueOf(60),
                    med.DicRatePlan, med.BillingSpans, med.MaxDecimalPrecision).Amount();
            candidates.add(new RateCandidate(0, "BDT", rate.rateamount.doubleValue(), firstMinute.doubleValue()));
        }

        return candidates.isEmpty()
                ? new TierRating(tier.DbName(), tier.PartnerId(), serviceGroupId, "no rate or package for the call", List.of())
                : new TierRating(tier.DbName(), tier.PartnerId(), serviceGroupId, "", candidates);
    }
}
