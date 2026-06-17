using Billing.Config.TenantConfigSync.Dependencies;

namespace Billing.Tests;

/// <summary>The profile reader maps the kebab-case YAML (admin-db, reseller-db-prefix, secret-ref) onto
/// the typed DatasourceOptions, and carries no credentials.</summary>
public class ProfileConfigReaderTests
{
    [Fact]
    public void Reads_datasource_block_with_kebab_keys()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var profileDir = Path.Combine(dir, "tenants", "ccl", "dev");
        Directory.CreateDirectory(profileDir);
        File.WriteAllText(Path.Combine(dir, "tenants.yml"),
            "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        File.WriteAllText(Path.Combine(profileDir, "profile-dev.yml"),
            "billing:\n" +
            "  datasource:\n" +
            "    host: \"10.0.0.5\"\n" +
            "    port: 3306\n" +
            "    admin-db: \"telcobright\"\n" +
            "    reseller-db-prefix: \"res_\"\n" +
            "    secret-ref: \"kv/billing/ccl-dev-db\"\n");

        var selection = ProfileConfigReader.ReadSelection(dir);
        var ds = ProfileConfigReader.ReadDatasource(dir, selection);

        Assert.True(ds.IsConfigured);
        Assert.Equal("10.0.0.5", ds.Host);
        Assert.Equal(3306, ds.Port);
        Assert.Equal("telcobright", ds.AdminDb);
        Assert.Equal("res_", ds.ResellerDbPrefix);
        Assert.Equal("kv/billing/ccl-dev-db", ds.SecretRef);
    }
}
