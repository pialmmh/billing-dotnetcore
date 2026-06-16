namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>One row of tenants.yml: a tenant this instance loads, and its active profile.</summary>
public sealed record SelectedTenant(string Name, bool Enabled, string Profile);

/// <summary>The parsed tenants.yml — which tenants load and their active profiles.</summary>
public sealed class TenantSelection
{
    public IReadOnlyList<SelectedTenant> Tenants { get; init; } = [];

    public IEnumerable<SelectedTenant> Enabled => Tenants.Where(t => t.Enabled);
}
