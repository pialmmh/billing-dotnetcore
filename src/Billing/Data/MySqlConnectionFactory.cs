using MySqlConnector;

namespace Billing.Data;

/// <summary>
/// Opens a <see cref="MySqlConnection"/> to a tenant's schema on the configured datasource — the write
/// target for the batch/post-call slice. Host/port come from the profile's <c>billing.datasource</c> block;
/// the username/password are supplied here (eventually resolved from OpenBao via the datasource secret-ref;
/// for the dev simulation they come from configuration). One connection per batch — the caller
/// (<see cref="MySqlCdrBatchRunner"/>) owns the single transaction.
/// </summary>
public sealed class MySqlConnectionFactory
{
    private readonly string _host;
    private readonly int _port;
    private readonly string _user;
    private readonly string _password;

    public MySqlConnectionFactory(string host, int port, string user, string password)
    {
        _host = host;
        _port = port;
        _user = user;
        _password = password;
    }

    /// <summary>True once a host + username are present (so the batch RPC can refuse cleanly otherwise).</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(_host) && !string.IsNullOrWhiteSpace(_user);

    /// <summary>Open a connection to the given schema (the tenant dbName) on the datasource.</summary>
    public MySqlConnection Open(string database)
    {
        var cs = $"Server={_host};Port={_port};Database={database};User ID={_user};Password={_password};";
        var conn = new MySqlConnection(cs);
        conn.Open();
        return conn;
    }
}
