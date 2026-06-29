package com.telcobright.billing.tenantconfigsync.internal.dto;

import java.util.List;

/**
 * One service group's wire config: the rating rules and the two validation checklists, the latter
 * as rule REFERENCES (name + optional threshold) — behaviour is NOT serialized, it is resolved from the
 * ValidationRuleRegistry. (Partner rules will join {@link #Rules} as another rule kind later.)
 */
public final class ServiceGroupConfigDto {
    public int ServiceGroupId;
    public boolean Disabled;
    public List<RatingRuleDto> Rules;
    public List<RuleRefDto> AnsweredChecklist;
    public List<RuleRefDto> UnansweredChecklist;
}
