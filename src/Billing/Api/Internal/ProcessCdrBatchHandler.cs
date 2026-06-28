using System.Text.Json;
using Billing.Service.Beans;
using Grpc.Core;
using Telcobright.Billing.V1;

namespace Billing.Service.Api.Internal;

/// <summary>
/// Adapts the gRPC <c>ProcessCdrBatch</c> RPC to the <see cref="CdrProcessor"/> bean: deserialize each cdr
/// from its JSON (the Kafka payload shape) into the full <c>cdr</c> POCO, hand the batch to the processor
/// (which resolves the tenant's config from config-manager and mediates + writes it), then map the outcome
/// back to the proto reply. All the processing lives in the bean; this is just the proto boundary.
/// </summary>
public sealed class ProcessCdrBatchHandler
{
    private static readonly JsonSerializerOptions CdrJson = new() { PropertyNameCaseInsensitive = true };

    private readonly CdrProcessor _cdrProcessor;

    public ProcessCdrBatchHandler(CdrProcessor cdrProcessor) => _cdrProcessor = cdrProcessor;

    public Task<CdrBatchResult> Handle(CdrBatchRequest request, ServerCallContext context)
    {
        List<MediationModel.cdr> cdrs;
        try
        {
            cdrs = new List<MediationModel.cdr>(request.CdrsJson.Count);
            foreach (var json in request.CdrsJson)
                cdrs.Add(JsonSerializer.Deserialize<MediationModel.cdr>(json, CdrJson)!);
        }
        catch (Exception ex)
        {
            return Task.FromResult(new CdrBatchResult { Error = "cdr json parse error: " + ex.Message });
        }

        var result = _cdrProcessor.ProcessBatch(request.Tenant, cdrs);
        if (result.Batch is null)
            return Task.FromResult(new CdrBatchResult { Committed = result.Committed, Error = result.Error ?? "" });

        var b = result.Batch;
        return Task.FromResult(new CdrBatchResult
        {
            Committed = true,
            Rated = b.Rated.Count,
            Errored = b.Errored.Count,
            CdrsWritten = b.CdrsWritten,
            ChargeablesWritten = b.ChargeablesWritten,
            CdrErrorsWritten = b.CdrErrorsWritten,
            TotalCharged = (double)b.TotalCharged,
        });
    }
}
