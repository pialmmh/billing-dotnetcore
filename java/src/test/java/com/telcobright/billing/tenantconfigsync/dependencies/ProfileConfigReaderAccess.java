package com.telcobright.billing.tenantconfigsync.dependencies;

/** Test access to the profile reader's package-private YAML parsers from another package's test. */
public final class ProfileConfigReaderAccess {
    private ProfileConfigReaderAccess() {}

    public static DatasourceOptions Datasource(String yaml) {
        return ProfileConfigReader.ReadDatasourceFromYaml(yaml);
    }
}
