package com.telcobright.billing.tenantconfigsync.internal.dto;

import java.math.BigDecimal;

/**
 * A validation-rule reference: the registered rule {@link #Rule} name + an optional
 * {@link #Data} threshold. No .NET type names, no polymorphic deserialization.
 */
public final class RuleRefDto {
    public String Rule;
    public BigDecimal Data;
}
