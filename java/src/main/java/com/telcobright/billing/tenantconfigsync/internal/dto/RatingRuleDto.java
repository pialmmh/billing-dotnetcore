package com.telcobright.billing.tenantconfigsync.internal.dto;

/** A rating rule on the wire — pure data (family id + direction + digit rules). */
public final class RatingRuleDto {
    public int IdServiceFamily;
    public int AssignDirection;
    public String DigitRulesData;
}
