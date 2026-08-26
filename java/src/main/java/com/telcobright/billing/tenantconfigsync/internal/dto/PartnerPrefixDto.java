package com.telcobright.billing.tenantconfigsync.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One served {@code prefixWisePartnerPrefixes} entry (legacy {@code partnerprefix}). Only {@code idPartner} is
 * consumed — it is the ANS operator id stamped onto the cdr by {@code AnsPrefixFinder}. Extra served fields
 * (id, prefixType, prefix, commonTG) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PartnerPrefixDto {
    public Integer idPartner;
    public String prefix;
}
