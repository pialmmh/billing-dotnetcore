package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.cdr.AnsPrefixFinder;
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
import com.telcobright.billing.mediation.servicefamilies.SfDomOffNetOutIcx;
import com.telcobright.billing.mediation.servicefamilies.SfXyzIcx;
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

    // The legacy MEF service-family container, as a fixed registry: SF1 (base A2Z), SF10 (A2Z+VAT), SF11,
    // SF20 (SG10 ICX/ANS vendor cost).
    private static List<IServiceFamily> DefaultFamilies() {
        return List.of(new SfA2Z(), new SfA2ZWithVatTax(), new SfDomOffNetInAns(), new SfDomOffNetOutIcx(),
                new SfXyzIcx());
    }

    /** idService of the service-wide ICX/ANS cost config (legacy SfDomOffNetOutIcx.Id), resolved for SG10. */
    private static final int IcxServiceId = 20;

    /**
     * idService of the domestic-incoming ANS customer family (legacy {@code SfDomOffNetInAns.Id} = SG11). Legacy
     * rates it via {@code GetServiceTuple} — the SERVICE-WIDE idService=11 tuple, matched on the TERMINATING
     * number — NOT the per-partner customer path. The chargeable is still tagged Customer.
     */
    private static final int DomOffNetInAnsServiceId = 11;

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(BasicCharge.class);

    /** SG 15 — international outgoing (00…). Rated by the service-wide Xyz-ICX plan, not the SG-rule loop. */
    private static final int IntlOutIptspServiceGroup = 15;
    /** idService of the ONE common international-outgoing Xyz plan (legacy {@code SfXyzIcx.Id}=7, plan 117). */
    private static final int XyzIcxServiceId = 7;

    /**
     * Run ALL of the detected service group's configured rating rules (legacy ExecuteRating) and return the
     * resulting chargeables (one per rule that matched a rate). Empty if no SG is detected, the SG is
     * disabled/unconfigured, or no rule produced a charge.
     */
    public List<acc_chargeable> Rate(cdr cdr, MediationContext mediation, Map<Integer, Partner> partners) {
        var match = _detection.Detect(cdr, partners);
        if (match == null) return List.of();
        cdr.ServiceGroup = match.ServiceGroupId();   // stamp the detected SG (legacy serviceGroup.Execute)
        StampAnsOperator(cdr, mediation);            // ANS operator ids from partnerprefix (legacy AnsPrefixFinder)

        // SG15 international-outgoing (00…): rated by its OWN service-wide Xyz-ICX pass — it carries no
        // customer/supplier RatingRule config (one common idService=7 plan), so it never enters the rule loop.
        if (match.ServiceGroupId() == IntlOutIptspServiceGroup) {
            return RateIntlOut(cdr, mediation, match);
        }

        ServiceGroupConfiguration sgConfig = mediation.ServiceGroupConfigurations.get(match.ServiceGroupId());
        if (sgConfig == null || sgConfig.Disabled()) return List.of();

        var chargeables = new ArrayList<acc_chargeable>();
        for (RatingRule rule : sgConfig.Rules().stream()                      // the rating-kind rules, in order
                .filter(r -> r instanceof RatingRule).map(r -> (RatingRule) r).collect(Collectors.toList())) {
            var chargeable = ChargeRule(cdr, mediation, match, rule);
            if (chargeable != null) chargeables.add(chargeable);
        }

        // ICX/ANS vendor COST leg (legacy SfDomOffNetOutIcx): every SG10 domestic-outgoing call carries a
        // service-wide idService=20 cost, resolved independently of the SG's customer/supplier tuples and matched
        // on the terminating number. No-op when the tenant has no idService=20 config (MatchServiceWideRate ->
        // null), so tenants without ICX config are unaffected.
        if (match.ServiceGroupId() == 10) {
            IServiceFamily icxFamily = _families.get(IcxServiceId);
            Rateext icxRate = MatchServiceWideRate(cdr, mediation, IcxServiceId, cdr.TerminatingCalledNumber);
            if (icxFamily != null && icxRate != null) {
                acc_chargeable icx = icxFamily.Charge(icxRate, cdr, match.ServiceGroupId(),
                        AssignmentDirection.Supplier, mediation);
                if (icx != null) chargeables.add(icx);
            }
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

    /**
     * SG15 international-outgoing rating. Resolves the ONE common Xyz-ICX plan SERVICE-WIDE (idService=7) on the
     * {@code 00}-stripped destination and charges {@link SfXyzIcx}. A FAILED / 0-duration call stays classified
     * SG15 but produces no chargeable (legacy: XyzRuleHelper builds a chargeable only when ChargingStatus==1) —
     * zero financial impact. An ANSWERED call with no international rate (or a missing USD conversion, thrown by
     * the family) FAILS LOUD so the call lands in cdrerror — it is never silently zero-billed.
     */
    private List<acc_chargeable> RateIntlOut(cdr cdr, MediationContext mediation, ServiceGroupMatch match) {
        boolean answered = cdr.ChargingStatus != null && cdr.ChargingStatus == 1
                && cdr.DurationSec != null && cdr.DurationSec.signum() > 0;

        IServiceFamily xyz = _families.get(XyzIcxServiceId);
        if (xyz == null) {
            if (answered) throw new IllegalStateException(
                    "SG15 answered call but the Xyz-ICX family (idService=7) is not registered — uniqueBillId="
                    + cdr.UniqueBillId);
            return List.of();
        }

        // Match with the number AS DIALED (only "+" removed), NOT the 00-stripped form: the RateCache keys every
        // prefix as techPrefix + rate.Prefix, and the international plan carries techPrefix "00" (rateplan.field4)
        // — so plan 117's '852' row is keyed '00852' and only the full dialed string '0085228866016' can hit it.
        // (Matching with the stripped number returned NULL for every intl destination — 2026-09-03 HK incident.)
        String dialed = cdr.OriginatingCalledNumber.startsWith("+")
                ? cdr.OriginatingCalledNumber.substring(1) : cdr.OriginatingCalledNumber;
        Rateext rate = MatchServiceWideRate(cdr, mediation, XyzIcxServiceId, dialed);
        if (rate == null) {
            if (answered) {
                throw new IllegalStateException("SG15 answered international call has NO rate (idService=7) for dest="
                        + cdr.OriginatingCalledNumber + " (matched-with=" + dialed + ", uniqueBillId="
                        + cdr.UniqueBillId + ") — the Outgoing XYZ international plan is missing/incomplete");
            }
            LOG.debugf("SG15 failed call (no charge, no rate needed): dest=%s uniqueBillId=%s",
                    cdr.OriginatingCalledNumber, cdr.UniqueBillId);
            return List.of();
        }
        if (!answered) {
            LOG.debugf("SG15 failed call classified but not charged (ChargingStatus=%s dur=%s) dest=%s",
                    cdr.ChargingStatus, cdr.DurationSec, cdr.OriginatingCalledNumber);
            return List.of();
        }

        acc_chargeable c = xyz.Charge(rate, cdr, IntlOutIptspServiceGroup, AssignmentDirection.Customer, mediation);
        LOG.infof("SG15 rated: dest=%s prefix=%s xRate=%s dur=%s X=%s Y(USD)=%s usdRate=%s Z=%s billed=%s tax2=%s",
                cdr.OriginatingCalledNumber, cdr.MatchedPrefixY, rate.rateamount, cdr.RoundedDuration,
                cdr.XAmount, cdr.YAmount, cdr.UsdRateY, cdr.ZAmount, c != null ? c.BilledAmount : null, cdr.Tax2);
        return c != null ? List.of(c) : List.of();
    }

    /**
     * Stamp the ANS operator ids from the tenant's partnerprefix (legacy {@code AnsPrefixFinder}, which each
     * SG's detection ran): {@code AnsIdTerm}/{@code AnsPrefixTerm} from the terminating (called) number,
     * {@code AnsIdOrig}/{@code AnsPrefixOrig} from the originating (calling) number. FALLBACK only — the Kafka
     * ingest path already carries these off the envelope, so we fill only what is unset (the finalize/multi-tier
     * path builds the cdr without an envelope). No-op when the tenant serves no partnerprefix.
     */
    private static void StampAnsOperator(cdr cdr, MediationContext mediation) {
        if (mediation.AnsPrefixes == null || mediation.AnsPrefixes.isEmpty()) return;
        if (cdr.AnsIdTerm == null && cdr.TerminatingCalledNumber != null)
            AnsPrefixFinder.FindTerminatingAnsPrefix(cdr, mediation.AnsPrefixes, cdr.TerminatingCalledNumber);
        if (cdr.AnsIdOrig == null && cdr.OriginatingCallingNumber != null)
            AnsPrefixFinder.FindOriginatingAnsPrefix(cdr, mediation.AnsPrefixes, cdr.OriginatingCallingNumber);
    }

    // One rating rule: resolve the family, look the rate up through the RateCache for the rule's direction,
    // and charge. The legacy A2ZRater path, per rule.
    private acc_chargeable ChargeRule(cdr cdr, MediationContext mediation, ServiceGroupMatch match, RatingRule rule) {
        var family = _families.get(rule.IdServiceFamily());
        if (family == null) return null;

        // SG11 domestic-incoming ANS (legacy SfDomOffNetInAns) resolves its rate SERVICE-WIDE — the idService=11
        // tuple with no partner/direction (legacy GetServiceTuple) — and matches on the TERMINATING number, not
        // the per-partner customer path. Resolving it partner-keyed (dir=1) finds nothing in a prod-shaped config
        // (no per-partner SG11 assign exists), so the leg silently drops to 0. Gate on the SG actually being SG11
        // (not merely family 11), so a config that plugs SF11 into another SG keeps the normal partner-keyed path.
        var rate = (match.ServiceGroupId() == DomOffNetInAnsServiceId
                        && rule.IdServiceFamily() == DomOffNetInAnsServiceId)
                ? MatchServiceWideRate(cdr, mediation, DomOffNetInAnsServiceId, cdr.TerminatingCalledNumber)
                : MatchRate(cdr, mediation, match, rule.AssignDirection());
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

    /**
     * Resolve a SERVICE-WIDE rate (no partner, no route) for an explicit idService — the legacy
     * {@code GetServiceTuple} path used by the SG10 ICX cost leg (idService=20). Resolves the service-scope
     * tuples for (idService, direction=None) and longest-prefixes {@code phoneNumber} over the RateCache.
     * Returns null when the tenant has no such service config or the number matches no prefix.
     */
    private static Rateext MatchServiceWideRate(cdr cdr, MediationContext mediation, int idService, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return null;
        var tuples = mediation.RatePlanResolver.Resolve(idService, AssignmentDirection.None.value, null, null);
        if (tuples.isEmpty()) return null;

        int category = (cdr.Category != null && cdr.Category > 0) ? cdr.Category : 1;
        int subCategory = (cdr.SubCategory != null && cdr.SubCategory > 0) ? cdr.SubCategory : 1;
        LocalDateTime answerTime = cdr.AnswerTime != null ? cdr.AnswerTime : cdr.StartTime;
        var day = new DateRange(answerTime.toLocalDate().atStartOfDay(),
                answerTime.toLocalDate().atStartOfDay().plusDays(1));
        var tups = tuples.stream()
                .map(t -> {
                    TupleByPeriod tp = new TupleByPeriod();
                    tp.IdAssignmentTuple = t.id;
                    tp.DRange = day;
                    tp.Priority = t.priority;
                    return tp;
                })
                .collect(Collectors.toList());
        return new PrefixMatcher(mediation.RateCache, phoneNumber, category, subCategory, tups, answerTime).MatchPrefix();
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
