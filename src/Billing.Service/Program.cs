using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Spi;
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

// Datasource for the post-call (summary) slice — loaded now; used when FinalizeAndSummarize lands.
builder.Services.AddSingleton(Options.Create(ProfileConfigReader.ReadDatasource(configRoot, selection)));

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
