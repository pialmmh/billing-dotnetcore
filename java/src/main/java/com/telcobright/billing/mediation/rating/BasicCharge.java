package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.mediation.servicefamilies.IServiceFamily;
import com.telcobright.billing.mediation.servicefamilies.SfA2Z;
import com.telcobright.billing.mediation.servicefamilies.SfA2ZWithVatTax;
import com.telcobright.billing.mediation.servicefamilies.SfDomOffNetInAns;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupDetection;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupMatch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The per-cdr charge: detect the service group -&gt; run that SG's CONFIGURED rating rules
 * ({@code ServiceGroupConfiguration.Rules}, legacy {@code ExecuteRating}). Each rating rule names a service
 * family (by id) and an assignment direction; for each, the rate-plan tuples are resolved by (idService,
 * direction, partner/route), the legacy {@link PrefixMatcher} longest-prefixes over the per-day
 * {@code RateCache} (matching a {@link Rateext}), and the rule's {@link IServiceFamily} computes the charge
 * -&gt; an {@code acc_chargeable}. The rating knobs (DicRatePlan / BillingSpans / MaxDecimalPrecision) ride on the
 * {@link MediationContext} and are threaded into family.Charge -&gt; A2ZRater.
 */
public final class BasicCharge {
    private final ServiceGroupDetection _detection;
    private final Map<Integer, IServiceFamily> _families;

    public BasicCharge(ServiceGroupDetection detection) {
        this(detection, null);
    }

    public BasicCharge(ServiceGroupDetection detection, List<IServiceFamily> families) {
        _detection = detection;
        var source = families != null ? families : DefaultFamilies();
        _families = source.stream().collect(Collectors.toMap(f -> f.Id(), f -> f));
    }

    /** The SG10+SG11 detection pair + the built-in service families — the ready instance. */
    public static BasicCharge Default() {
        return new BasicCharge(ServiceGroupDetection.Default());
    }

    // The legacy MEF service-family container, as a fixed registry: SF1 (base A2Z), SF10 (A2Z+VAT), SF11.
    private static List<IServiceFamily> DefaultFamilies() {
        return List.of(new SfA2Z(), new SfA2ZWithVatTax(), new SfDomOffNetInAns());
    }

    /**
     * Run ALL of the detected service group's configured rating rules (legacy ExecuteRating) and return the
     * resulting chargeables (one per rule that matched a rate). Empty if no SG is detected, the SG is
     * disabled/unconfigured, or no rule produced a charge.
     */
    public List<acc_chargeable> Rate(cdr cdr, MediationContext mediation, Map<Integer, Partner> partners) {
        var match = _detection.Detect(cdr, partners);
        if (match == null) return List.of();
        cdr.ServiceGroup = match.ServiceGroupId();   // stamp the detected SG (legacy serviceGroup.Execute)
        ServiceGroupConfiguration sgConfig = mediation.ServiceGroupConfigurations.get(match.ServiceGroupId());
        if (sgConfig == null || sgConfig.Disabled()) return List.of();

        var chargeables = new ArrayList<acc_chargeable>();
        for (RatingRule rule : sgConfig.Rules().stream()                      // the rating-kind rules, in order
                .filter(r -> r instanceof RatingRule).map(r -> (RatingRule) r).collect(Collectors.toList())) {
            var chargeable = ChargeRule(cdr, mediation, match, rule);
            if (chargeable != null) chargeables.add(chargeable);
        }
        return chargeables;
    }

    /**
     * The single chargeable for the detected SG's first configured rule in the given direction — the per-leg
     * convenience the per-call finalize path uses. Null if not detected / no such rule / no rate.
     */
    public acc_chargeable Compute(cdr cdr, AssignmentDirection direction, MediationContext mediation,
            Map<Integer, Partner> partners) {
        var match = _detection.Detect(cdr, partners);
        if (match == null) return null;
        ServiceGroupConfiguration sgConfig = mediation.ServiceGroupConfigurations.get(match.ServiceGroupId());
        if (sgConfig == null || sgConfig.Disabled()) return null;

        var rule = sgConfig.Rules().stream()
                .filter(r -> r instanceof RatingRule).map(r -> (RatingRule) r)
                .filter(r -> r.AssignDirection() == direction.value)
                .findFirst().orElse(null);
        return rule == null ? null : ChargeRule(cdr, mediation, match, rule);
    }

    /**
     * Detect the service group and match the CUSTOMER rate for a call WITHOUT charging it — the pre-call
     * (max-rate / admission) path. Stamps {@code cdr.ServiceGroup}; returns the detected SG id (0 = not
     * detected) and the matched {@link Rateext} (null if no SG / no rate).
     */
    public MatchCustomerRateResult MatchCustomerRate(cdr cdr, MediationContext mediation, Map<Integer, Partner> partners) {
        var match = _detection.Detect(cdr, partners);
        if (match == null) return new MatchCustomerRateResult(0, null);
        cdr.ServiceGroup = match.ServiceGroupId();
        var rate = MatchRate(cdr, mediation, match, AssignmentDirection.Customer.value);
        return new MatchCustomerRateResult(match.ServiceGroupId(), rate);
    }

    // One rating rule: resolve the family, look the rate up through the RateCache for the rule's direction,
    // and charge. The legacy A2ZRater path, per rule.
    private acc_chargeable ChargeRule(cdr cdr, MediationContext mediation, ServiceGroupMatch match, RatingRule rule) {
        var family = _families.get(rule.IdServiceFamily());
        if (family == null) return null;

        var rate = MatchRate(cdr, mediation, match, rule.AssignDirection());
        if (rate == null) return null;

        return family.Charge(rate, cdr, match.ServiceGroupId(), directionFromValue(rule.AssignDirection()), mediation);
    }

    // Resolve the rate-plan tuples for the (service group, direction, partner) and longest-prefix the dialed
    // number over the per-day RateCache (legacy PrefixMatcher). Shared by the charge + the max-rate paths.
    private static Rateext MatchRate(cdr cdr, MediationContext mediation, ServiceGroupMatch match, int assignDirection) {
        // Customer leg keys off the in-partner, supplier leg off the out-partner (legacy A2ZRater).
        Integer idPartner = (assignDirection == AssignmentDirection.Supplier.value)
                ? cdr.OutPartnerId : cdr.InPartnerId;

        var tuples = mediation.RatePlanResolver.Resolve(match.ServiceGroupId(), assignDirection, idPartner, null);
        if (tuples.isEmpty()) return null;

        // legacy ExecuteA2ZRating: tempCategory>0 ? tempCategory : 1 (0/null both default to 1=call/voice).
        int category = (cdr.Category != null && cdr.Category > 0) ? cdr.Category : 1;
        int subCategory = (cdr.SubCategory != null && cdr.SubCategory > 0) ? cdr.SubCategory : 1;
        LocalDateTime answerTime = cdr.AnswerTime != null ? cdr.AnswerTime : cdr.StartTime;

        var day = new DateRange(answerTime.toLocalDate().atStartOfDay(), answerTime.toLocalDate().atStartOfDay().plusDays(1));
        var tups = tuples.stream()
                .map(t -> {
                    TupleByPeriod tp = new TupleByPeriod();
                    tp.IdAssignmentTuple = t.id;
                    tp.DRange = day;
                    tp.Priority = t.priority;
                    return tp;
                })
                .collect(Collectors.toList());
        // FAITHFUL to legacy A2ZRater (no digit rules configured): the rate is matched on the RAW dialed
        // number — OriginatingCalledNumber for the Customer direction, TerminatingCalledNumber for the
        // Supplier direction. The SG detector's normalized number feeds ONLY the ANS-prefix finder (legacy
        // Execute never wrote it back to the cdr); rate-table prefixes are country-code-qualified (literal
        // 880… rows or a plan field4 tech-prefix), so matching the normalized national form finds nothing.
        String phoneNumber = (assignDirection == AssignmentDirection.Supplier.value)
                ? cdr.TerminatingCalledNumber : cdr.OriginatingCalledNumber;
        if (phoneNumber == null || phoneNumber.isEmpty()) return null;   // no number can match no prefix
        return new PrefixMatcher(mediation.RateCache, phoneNumber,
                category, subCategory, tups, answerTime).MatchPrefix();
    }

    // C# `(AssignmentDirection)intValue` — map the legacy int direction back to the enum by its value.
    private static AssignmentDirection directionFromValue(int value) {
        for (AssignmentDirection d : AssignmentDirection.values())
            if (d.value == value) return d;
        throw new IllegalArgumentException("unknown AssignmentDirection: " + value);
    }

    /**
     * Java carrier for the C# named ValueTuple {@code (int ServiceGroupId, Rateext? Rate)} returned by
     * {@link #MatchCustomerRate}.
     */
    public record MatchCustomerRateResult(int ServiceGroupId, Rateext Rate) {
    }
}
