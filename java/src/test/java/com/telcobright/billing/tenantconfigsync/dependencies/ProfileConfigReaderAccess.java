package com.telcobright.billing.tenantconfigsync.dependencies;

/** Test access to the profile reader's package-private YAML parsers from another package's test. */
public final class ProfileConfigReaderAccess {
    private ProfileConfigReaderAccess() {}

    public static DatasourceOptions Datasource(String yaml) {
        return ProfileConfigReader.ReadDatasourceFromYaml(yaml);
    }

    public static TenantConfigSyncOptions Sync(String yaml) {
        return ProfileConfigReader.ReadOptionsFromYaml(yaml);
    }

    public static CdrIngestOptions Ingest(String yaml) {
        return ProfileConfigReader.ReadCdrIngestFromYaml(yaml);
    }

    public static SummaryOutboxOptions Summary(String yaml) {
        return ProfileConfigReader.ReadSummaryFromYaml(yaml);
    }
}
