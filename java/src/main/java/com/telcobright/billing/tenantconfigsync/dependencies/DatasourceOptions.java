package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * The tenant's database coordinates for the post-call / batch write slice (FinalizeAndSummarize,
 * ProcessCdrBatch) — writing the CDR and rolling up summaries into the admin schema and each reseller's
 * {@code res_NNN} schema. The rating path does NOT use this (it is config-manager-fed). For THIS project the
 * username + password live inline in the profile YAML (no OpenBao); they are read by ProfileConfigReader.
 */
public final class DatasourceOptions {
    public static final String MySql = "mysql";
    public static final String PostgreSql = "postgresql";

    /** The write target of this profile's tenant ({@code billing.datasource.kind}): {@code mysql} (the default —
     * a tenant is a database) or {@code postgresql} (a tenant is a schema of {@link #Database}). */
    public String Kind = MySql;
    public String Host = "";
    public int Port = 3306;
    /** PostgreSQL only: the one switch database every tenant is a schema of ({@code billing.datasource.database}). */
    public String Database = "";
    /** PostgreSQL only ({@code billing.datasource.postgres.*}): the role that reads billing-core's tables and deletes
     * from the summary outbox; the roles that only read; the month partitions a new table is made with. */
    public String PostgresSummaryServiceRole = "summary_service";
    public java.util.List<String> PostgresReaderRoles = java.util.List.of("ad_sphere");
    public int PostgresMonthsBack = 1;
    public int PostgresMonthsAhead = 3;

    public boolean IsPostgres() {
        return PostgreSql.equals(Kind);
    }

    /** Admin/operator schema; each reseller owns {@link #ResellerDbPrefix} + its id. */
    public String AdminDb = "";
    public String ResellerDbPrefix = "res_";

    /** Inline DB credentials from the profile YAML — for the deployments that keep them there. */
    public String Username = "";
    public String Password = "";
    /** {@code billing.datasource.password-ref}: {@code env:<VAR>} — the password is read from that environment
     * variable at start, never from the YAML (the secreteer rule; see {@link DatasourceSecret}). */
    public String PasswordRef = "";

    /** True once a host has been configured (the block is present in the active profile). */
    public boolean IsConfigured() {
        return !(Host == null || Host.isBlank());
    }
}
