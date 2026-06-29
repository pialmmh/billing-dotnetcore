package com.telcobright.billing.tenantconfigsync.internal.dto;

import java.util.Map;

// Wire shapes for config-manager's /get-specific-tenant-root response. Plain mutable graphs so
// Jackson maps cleanly; the immutable Model is built from these (the routesphere pattern:
// deserialize, then construct the immutable snapshot). JSON keys match routesphere's payload
// (case-insensitive matching is enabled on the deserializer, mirroring the C# PropertyNameCaseInsensitive).
//
// Faithful-port note: the C# DTOs were `internal`; in Java they must be `public` so the mapper in the
// sibling `internal` package can use them across the package boundary.

public final class TenantDto {
    public String Name;
    public String DbName;
    public String Parent;
    public Map<String, TenantDto> Children;
    public DynamicContextDto Context;
}
