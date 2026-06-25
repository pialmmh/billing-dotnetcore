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

// The connection factory for the batch write target. Host/port default to the profile's datasource; the
// username/password come from configuration (dev stopgap — OpenBao secret-ref resolution lands later).
// Override any of them for local testing, e.g. Billing__Db__Host=127.0.0.1 Billing__Db__User=root.
builder.Services.AddSingleton(new MySqlConnectionFactory(
    builder.Configuration["Billing:Db:Host"] ?? datasource.Host,
    int.TryParse(builder.Configuration["Billing:Db:Port"], out var dbPort) ? dbPort : datasource.Port,
    builder.Configuration["Billing:Db:User"] ?? "",
    builder.Configuration["Billing:Db:Password"] ?? ""));
builder.Services.AddSingleton(MySqlCdrBatchRunner.Default());

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
