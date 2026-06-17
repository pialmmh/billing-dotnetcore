namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>
/// The tenant's database coordinates for the post-call slice (FinalizeAndSummarize) — writing the CDR
/// and rolling up summaries into the admin schema and each reseller's <c>res_NNN</c> schema. The rating
/// path does NOT use this (it is config-manager-fed). Non-secret only: host/port/db live here; the
/// username + password are resolved at startup from the secret store (OpenBao) via <see cref="SecretRef"/>,
/// never from YAML/env (code-master secrets rule).
/// </summary>
public sealed class DatasourceOptions
{
    public string Host { get; set; } = "";
    public int Port { get; set; } = 3306;

    /// <summary>Admin/operator schema; each reseller owns <see cref="ResellerDbPrefix"/> + its id.</summary>
    public string AdminDb { get; set; } = "";
    public string ResellerDbPrefix { get; set; } = "res_";

    /// <summary>Secret-store (OpenBao) KV path holding this datasource's username + password.</summary>
    public string? SecretRef { get; set; }

    /// <summary>True once a host has been configured (the block is present in the active profile).</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(Host);
}
