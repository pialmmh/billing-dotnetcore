package com.telcobright.billing.tenantconfigsync.internal.dto;

import com.telcobright.billing.mediation.context.ServiceCategory;
import com.telcobright.billing.mediation.context.ServiceGroupRule;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;

import java.util.List;
import java.util.Map;

public final class MediationContextDto {
    public Map<Integer, ServiceCategory> Categories;
    public List<ServiceGroupRule> ServiceGroupRules;

    /**
     * The verbatim legacy rate-plan-assignment tuples (idService + AssignDirection +
     * idpartner/route + priority), each carrying its nested rateassigns. config-manager serves this
     * legacy-shaped JSON from the existing rateplanassignmenttuple/rateassign tables. Absent until it
     * does, leaving the resolver empty.
     */
    public List<rateplanassignmenttuple> RatePlanAssignmentTuples;

    /**
     * The served legacy {@code MediationContext.BillingSpans} (uom -> seconds): the {@code enumbillingspan}
     * lookup keyed by uom string, each value carrying the seconds in its {@code value} field (its
     * ofbiz_uom_Id/Type may be null on the wire — the MAP KEY is the uom the rater looks up). config-manager
     * serves this from the existing {@code enumbillingspan} table. Absent until it does, in which case the
     * mapper falls back to the built-in standard ofbiz uom table.
     */
    public Map<String, com.telcobright.billing.mediation.engine.models.enumbillingspan> BillingSpans;

    /**
     * Per-service-group configuration (rating rules + validation checklists). Absent until
     * config-manager serves it, in which case the built-in defaults apply.
     */
    public Map<Integer, ServiceGroupConfigDto> ServiceGroupConfigurations;

    /** The common (all-cdr) validation checklist, as rule references. */
    public List<RuleRefDto> CommonChecklist;
}
