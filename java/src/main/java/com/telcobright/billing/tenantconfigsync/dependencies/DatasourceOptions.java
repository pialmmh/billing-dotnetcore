package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * The tenant's database coordinates for the post-call / batch write slice (FinalizeAndSummarize,
 * ProcessCdrBatch) — writing the CDR and rolling up summaries into the admin schema and each reseller's
 * {@code res_NNN} schema. The rating path does NOT use this (it is config-manager-fed). For THIS project the
 * username + password live inline in the profile YAML (no OpenBao); they are read by ProfileConfigReader.
 */
public final class DatasourceOptions {
    public String Host = "";
    public int Port = 3306;

    /** Admin/operator schema; each reseller owns {@link #ResellerDbPrefix} + its id. */
    public String AdminDb = "";
    public String ResellerDbPrefix = "res_";

    /** Inline DB credentials from the profile YAML (this project keeps them in config, not OpenBao). */
    public String Username = "";
    public String Password = "";

    /** True once a host has been configured (the block is present in the active profile). */
    public boolean IsConfigured() {
        return !(Host == null || Host.isBlank());
    }
}
