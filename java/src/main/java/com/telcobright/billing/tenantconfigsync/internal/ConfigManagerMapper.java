package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.model.RatePlan;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.mediation.validation.ValidationRuleRegistry;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RuleRefDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.ServiceGroupConfigDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.mediation.rating.ratecaching.RateRowsByDateProvider;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the immutable Model (Tenant tree + DynamicContext + MediationContext) from the
 * config-manager wire DTOs. One reason to change: the wire shape. Computed lookups (index, ancestor
 * chains) are added afterwards by {@link TenantTreeBuilder}.
 */
final class ConfigManagerMapper {

    private static final LocalDateTime MinDate = LocalDateTime.of(1, 1, 1, 0, 0); // C# DateTime.MinValue

    private ConfigManagerMapper() {
    }

    public static Tenant ToTenant(TenantDto dto) {
        // Back-compat (tests): no client wired -> the RateCache serves today/tomorrow but cannot back-fill.
        return ToTenant(dto, null, RateCache.DEFAULT_MAX_DAYS);
    }

    public static Tenant ToTenant(TenantDto dto, IConfigManagerClient client, int maxDays) {
        Tenant t = new Tenant();
        t.Name = dto.Name != null ? dto.Name : "";
        t.DbName = dto.DbName != null ? dto.DbName : "";
        t.Parent = dto.Parent;
        t.Children = dto.Children == null
            ? new HashMap<>()
            : dto.Children.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ToTenant(e.getValue(), client, maxDays)));
        t.Context = ToContext(dto.Context, t.DbName, client, maxDays);
        return t;
    }

    private static DynamicContext ToContext(DynamicContextDto dto, String tenantDbName,
            IConfigManagerClient client, int maxDays) {
        if (dto == null) {
            return DynamicContext.Empty;
        }
        DynamicContext ctx = new DynamicContext();
        ctx.Partners = dto.Partners != null ? dto.Partners : new HashMap<>();
        ctx.RatePlans = dto.RatePlans != null ? dto.RatePlans : new HashMap<>();
        ctx.RatePlanWiseTodaysRates = dto.RatePlanWiseTodaysRates != null
            ? new HashMap<>(dto.RatePlanWiseTodaysRates)
            : new HashMap<>();
        ctx.RateAssignsCustomer = dto.RateAssignsCustomer != null ? dto.RateAssignsCustomer : List.of();
        ctx.RateAssignsSupplier = dto.RateAssignsSupplier != null ? dto.RateAssignsSupplier : List.of();
        ctx.PartnerIdWisePackageAccounts = dto.PartnerIdWisePackageAccounts != null
            ? new HashMap<>(dto.PartnerIdWisePackageAccounts)
            : new HashMap<>();

        // Pre-warm today + tomorrow from the pushed snapshot; the provider fetches OLDER days on demand
        // (back-processing) via /get-rates-by-date. today/tomorrow therefore never wait on a network call.
        // today's rows double as the fallback when no client is wired (tests) — legacy today-for-all-days.
        LocalDate today = LocalDate.now();
        Map<Integer, List<rate>> todayRows = ToRateRowsByRatePlan(ctx.RatePlanWiseTodaysRates);
        Map<LocalDate, Map<Integer, List<rate>>> prewarmed = new HashMap<>();
        prewarmed.put(today, todayRows);
        if (dto.RatePlanWiseTomorrowsRates != null)
            prewarmed.put(today.plusDays(1), ToRateRowsByRatePlan(dto.RatePlanWiseTomorrowsRates));
        RateRowsByDateProvider rateRowsProvider =
                new SnapshotBackfillRateRows(tenantDbName, prewarmed, todayRows, client);

        ctx.MediationContext = ToMediation(dto.MediationContext, ctx.RatePlans, rateRowsProvider, maxDays);
        return ctx;
    }

    private static MediationContext ToMediation(MediationContextDto dto,
            Map<Integer, RatePlan> ratePlans,
            RateRowsByDateProvider rateRowsProvider,
            int maxDays) {
        Map<Integer, ServiceGroupConfiguration> sgConfigs = (dto == null || dto.ServiceGroupConfigurations == null)
            ? null
            : dto.ServiceGroupConfigurations.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ToSgConfig(e.getValue())));

        // The legacy JOIN, fed from config: the resolver (which plan applies) + the per-day RateCache (the
        // rates) are both built from this tenant's rate-plan-assignment tuples (each carrying its rateassign
        // JOIN rows). The RateCache loader joins tuple -> rateassign(Inactive=idRatePlan) -> rate plan -> the
        // actual rate rows, which come per-day from the provider (snapshot for today/tomorrow, fetched otherwise).
        List<rateplanassignmenttuple> tuples = (dto != null && dto.RatePlanAssignmentTuples != null)
            ? dto.RatePlanAssignmentTuples : List.of();

        Map<String, rateplan> dicRatePlan = ToDicRatePlan(ratePlans);
        Map<String, enumbillingspan> billingSpans = ResolveBillingSpans(dto);

        return MediationContext.ForRating(
            tuples,
            rateRowsProvider,
            dicRatePlan,
            billingSpans,
            8,                                    // CdrSetting.MaxDecimalPrecision default
            dto != null ? dto.Categories : null,
            dto != null ? dto.ServiceGroupRules : null,
            sgConfigs,                            // null -> the built-in default SG configs
            dto != null ? ToChecklist(dto.CommonChecklist) : null,
            maxDays);
    }

    // ratePlans -> DicRatePlan (idrateplan as string -> engine rateplan). The served RatePlan now carries
    // techPrefix (field4), the BillingSpan uom and RateAmountRoundupDecimal, so use the served values; fall back
    // ONLY when a value is absent: field4 -> "" (no tech prefix), BillingSpan -> the per-minute uom (TF_min).
    private static Map<String, rateplan> ToDicRatePlan(Map<Integer, RatePlan> ratePlans) {
        Map<String, rateplan> dic = new HashMap<>();
        if (ratePlans == null) return dic;
        for (var e : ratePlans.entrySet()) {
            RatePlan plan = e.getValue();
            rateplan rp = new rateplan();
            rp.id = e.getKey();
            rp.RatePlanName = plan != null ? plan.Name : "";
            rp.field4 = (plan != null && plan.Field4 != null) ? plan.Field4 : "";
            rp.BillingSpan = (plan != null && plan.BillingSpan != null) ? plan.BillingSpan : "TF_min";
            rp.RateAmountRoundupDecimal = plan != null ? plan.RateAmountRoundupDecimal : null;
            dic.put(Integer.toString(e.getKey()), rp);
        }
        return dic;
    }

    // ratePlanWiseTodaysRates (planId -> prefix -> served Rate) -> rateRowsByRatePlan (planId -> engine rate
    // rows), via ToEngineRate which now maps the FULL served row.
    static Map<Integer, List<rate>> ToRateRowsByRatePlan(Map<Integer, Map<String, Rate>> served) {
        Map<Integer, List<rate>> out = new HashMap<>();
        if (served == null) return out;
        for (var planEntry : served.entrySet()) {
            List<rate> rows = new ArrayList<>();
            if (planEntry.getValue() != null)
                for (var r : planEntry.getValue().values())
                    rows.add(ToEngineRate(r));
            out.put(planEntry.getKey(), rows);
        }
        return out;
    }

    // Map the FULL served rate row into the engine rate. Use the served values; fall back ONLY when the served
    // value is null: startdate -> MinDate (today's-rates are valid); enddate stays null = open; Category/SubCategory
    // -> 1 (voice). billingspan / RateAmountRoundupDecimal / OtherAmount1..10 are taken verbatim from the served row.
    private static rate ToEngineRate(Rate s) {
        rate r = new rate();
        r.id = s.Id;
        r.Prefix = s.Prefix;
        r.idrateplan = s.IdRatePlan;
        r.ProductId = s.ProductId != null ? s.ProductId : 0;
        // C# `decimal` (non-nullable) defaulted a missing JSON field to 0 — a null here would NPE
        // deep in the raters (MaxRateTierRater.doubleValue / A2ZRater's flat surcharge add).
        r.rateamount = s.RateAmount != null ? s.RateAmount : java.math.BigDecimal.ZERO;
        r.CountryCode = s.CountryCode;
        r.Category = (byte) (s.Category != null ? s.Category : 1);
        r.SubCategory = (byte) (s.SubCategory != null ? s.SubCategory : 1);
        r.Resolution = s.Resolution != null ? s.Resolution : 0;
        r.MinDurationSec = s.MinDurationSec != null ? s.MinDurationSec.floatValue() : 0f;
        r.SurchargeTime = s.SurchargeTime != null ? s.SurchargeTime : 0;
        r.SurchargeAmount = s.SurchargeAmount != null ? s.SurchargeAmount : java.math.BigDecimal.ZERO;
        r.Inactive = s.Inactive != null ? s.Inactive : 0;
        r.startdate = s.StartDate != null ? s.StartDate : MinDate;
        r.enddate = s.EndDate;
        r.billingspan = s.BillingSpan;
        r.RateAmountRoundupDecimal = s.RateAmountRoundupDecimal;
        r.OtherAmount1 = s.OtherAmount1;
        r.OtherAmount2 = s.OtherAmount2;
        r.OtherAmount3 = s.OtherAmount3;
        r.OtherAmount4 = s.OtherAmount4;
        r.OtherAmount5 = s.OtherAmount5;
        r.OtherAmount6 = s.OtherAmount6;
        r.OtherAmount7 = s.OtherAmount7;
        r.OtherAmount8 = s.OtherAmount8;
        r.OtherAmount9 = s.OtherAmount9;
        r.OtherAmount10 = s.OtherAmount10;
        return r;
    }

    // The served legacy MediationContext.BillingSpans (uom -> enumbillingspan carrying the seconds) is the PRIMARY
    // source. Fall back to the built-in StandardBillingSpans() ONLY when config-manager hasn't served the table
    // (null/empty) — so existing tests + deployments without the served enumbillingspan table still resolve a
    // rate plan's BillingSpan uom to seconds.
    private static Map<String, enumbillingspan> ResolveBillingSpans(MediationContextDto dto) {
        if (dto != null && dto.BillingSpans != null && !dto.BillingSpans.isEmpty()) {
            return dto.BillingSpans;
        }
        return StandardBillingSpans();
    }

    // FALLBACK ONLY (see ResolveBillingSpans): the standard ofbiz time-frequency uoms -> seconds, used when the
    // served billingSpans table is absent. A rate plan's BillingSpan uom string is resolved against this by A2ZRater.
    private static Map<String, enumbillingspan> StandardBillingSpans() {
        Map<String, enumbillingspan> m = new HashMap<>();
        m.put("TF_s", Span("TF_s", 1));
        m.put("TF_min", Span("TF_min", 60));
        m.put("TF_hr", Span("TF_hr", 3600));
        m.put("TF_day", Span("TF_day", 86400));
        return m;
    }

    private static enumbillingspan Span(String uom, long seconds) {
        enumbillingspan e = new enumbillingspan();
        e.ofbiz_uom_Id = uom;
        e.value = seconds;
        return e;
    }

    private static ServiceGroupConfiguration ToSgConfig(ServiceGroupConfigDto dto) {
        List<Rule> rules = dto.Rules == null
            ? List.of()
            : dto.Rules.stream()
                .map(r -> (Rule) new RatingRule(r.IdServiceFamily, r.AssignDirection, r.DigitRulesData))
                .collect(Collectors.toList());
        return new ServiceGroupConfiguration(
            dto.ServiceGroupId,
            dto.Disabled,
            rules,
            ToChecklist(dto.AnsweredChecklist),
            ToChecklist(dto.UnansweredChecklist));
    }

    // Bind validation-rule REFERENCES (name + optional data) to behaviour via the registry; unknown name
    // throws at config-load (fail-fast), not at mediation time.
    private static List<IValidationRule<cdr>> ToChecklist(List<RuleRefDto> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream()
            .map(r -> ValidationRuleRegistry.Default.Resolve(r.Rule != null ? r.Rule : "", r.Data))
            .collect(Collectors.toList());
    }
}
