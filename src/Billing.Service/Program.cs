using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Spi;
using Billing.Data;
using Billing.Mediation.Rating;
using Billing.Service.Adapters;
using Billing.Service.Services;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddGrpc();

// The rating engine (stub rater for now; swapped for the real one when MediationContext lands).
builder.Services.AddMediationRating();

// --- Tenant config sync ---------------------------------------------------------------
// On start, build every enabled tenant's DynamicContext (+ MediationContext) from
// config-manager; a Kafka config_event_loader_<tenant> message triggers a debounced reload.
var configRoot = Path.Combine(builder.Environment.ContentRootPath, "config");
var selection = ProfileConfigReader.ReadSelection(configRoot);
var configOptions = ProfileConfigReader.ReadOptions(configRoot, selection);

builder.Services.AddTenantConfigSync(configOptions, selection);

// Datasource for the post-call / batch write slice (FinalizeAndSummarize, ProcessCdrBatch).
var datasource = ProfileConfigReader.ReadDatasource(configRoot, selection);
builder.Services.AddSingleton(Options.Create(datasource));

// The connection factory for the batch write target. Host/port/creds come 100% from the profile's datasource
// block (this project keeps the DB username/password inline in the YAML, not OpenBao). No env overrides.
builder.Services.AddSingleton(new MySqlConnectionFactory(
    datasource.Host, datasource.Port, datasource.Username, datasource.Password));
builder.Services.AddSingleton(MySqlCdrBatchRunner.Default());

// Decoupled summary hand-off — read 100% from the profile's billing.summary block (no env). Off by default =
// inline summaries (legacy). When enabled, a cdr batch writes a compressed summary_affected outbox row (atomic
// with the cdr write) and fires a best-effort ping; the ping is a no-op when bootstrap-servers is empty.
builder.Services.AddSingleton(Options.Create(ProfileConfigReader.ReadSummary(configRoot, selection)));
builder.Services.AddSingleton<SummaryPingPublisher>();

// The Kafka adapter is the host-provided config-event source — registered only when enabled,
// so without it config loads once on start and never reloads (absence is a valid setup).
if (configOptions.ConfigEvents.Enabled)
{
    builder.Services.AddSingleton<IConfigEventSource>(sp => new KafkaConfigEventSource(
        selection, Options.Create(configOptions),
        sp.GetRequiredService<ILogger<KafkaConfigEventSource>>()));
}

var app = builder.Build();

app.MapGrpcService<BillingServiceImpl>();
app.MapGet("/", () => "Telcobright Billing/Rating gRPC service. Connect with a gRPC client (see Protos/billing.proto).");

app.Run();
