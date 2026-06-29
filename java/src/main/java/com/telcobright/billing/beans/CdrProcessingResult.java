package com.telcobright.billing.beans;

import com.telcobright.billing.mediation.cdr.CdrBatchResult;

/**
 * The outcome of {@link CdrProcessor#ProcessBatch}: either committed (with the engine's
 * {@link CdrBatchResult}) or a non-committed failure with the reason. Entry-point adapters map this
 * onto their own response shape (e.g. the gRPC reply).
 */
public record CdrProcessingResult(boolean Committed, String Error, CdrBatchResult Batch) {
    public static CdrProcessingResult Ok(CdrBatchResult batch) {
        return new CdrProcessingResult(true, null, batch);
    }

    public static CdrProcessingResult Failed(String error) {
        return new CdrProcessingResult(false, error, null);
    }
}
