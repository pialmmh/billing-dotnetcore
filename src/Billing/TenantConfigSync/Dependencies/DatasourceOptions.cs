namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>
/// The tenant's database coordinates for the post-call / batch write slice (FinalizeAndSummarize,
/// ProcessCdrBatch) — writing the CDR and rolling up summaries into the admin schema and each reseller's
/// <c>res_NNN</c> schema. The rating path does NOT use this (it is config-manager-fed). For THIS project the
/// username + password live inline in the profile YAML (no OpenBao); they are read by ProfileConfigReader.
/// </summary>
public sealed class DatasourceOptions
{
    public string Host { get; set; } = "";
    public int Port { get; set; } = 3306;

    /// <summary>Admin/operator schema; each reseller owns <see cref="ResellerDbPrefix"/> + its id.</summary>
    public string AdminDb { get; set; } = "";
    public string ResellerDbPrefix { get; set; } = "res_";

    /// <summary>Inline DB credentials from the profile YAML (this project keeps them in config, not OpenBao).</summary>
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";

    /// <summary>True once a host has been configured (the block is present in the active profile).</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(Host);
}
