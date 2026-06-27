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
            "    username: \"billing_user\"\n" +
            "    password: \"s3cr3t\"\n");

        var selection = ProfileConfigReader.ReadSelection(dir);
        var ds = ProfileConfigReader.ReadDatasource(dir, selection);

        Assert.True(ds.IsConfigured);
        Assert.Equal("10.0.0.5", ds.Host);
        Assert.Equal(3306, ds.Port);
        Assert.Equal("telcobright", ds.AdminDb);
        Assert.Equal("res_", ds.ResellerDbPrefix);
        Assert.Equal("billing_user", ds.Username);
        Assert.Equal("s3cr3t", ds.Password);
    }

    [Fact]
    public void Reads_summary_block_with_kebab_keys()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var profileDir = Path.Combine(dir, "tenants", "ccl", "dev");
        Directory.CreateDirectory(profileDir);
        File.WriteAllText(Path.Combine(dir, "tenants.yml"),
            "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        File.WriteAllText(Path.Combine(profileDir, "profile-dev.yml"),
            "billing:\n" +
            "  summary:\n" +
            "    enabled: true\n" +
            "    entity-type: \"cdr\"\n" +
            "    ping-topic: \"cdr_summary_ping\"\n" +
            "    bootstrap-servers: \"103.95.96.78:9092\"\n");

        var s = ProfileConfigReader.ReadSummary(dir, ProfileConfigReader.ReadSelection(dir));

        Assert.True(s.Enabled);
        Assert.Equal("cdr", s.EntityType);
        Assert.Equal("cdr_summary_ping", s.PingTopic);
        Assert.Equal("103.95.96.78:9092", s.BootstrapServers);
    }

    [Fact]
    public void Summary_defaults_to_disabled_when_block_absent()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var profileDir = Path.Combine(dir, "tenants", "ccl", "dev");
        Directory.CreateDirectory(profileDir);
        File.WriteAllText(Path.Combine(dir, "tenants.yml"),
            "tenants:\n  - name: ccl\n    enabled: true\n    profile: dev\n");
        File.WriteAllText(Path.Combine(profileDir, "profile-dev.yml"),
            "billing:\n  datasource:\n    host: \"x\"\n");

        var s = ProfileConfigReader.ReadSummary(dir, ProfileConfigReader.ReadSelection(dir));

        Assert.False(s.Enabled);                            // off by default = legacy inline summaries
        Assert.Equal("cdr_summary_ping", s.PingTopic);      // default preserved when unset
    }
}
