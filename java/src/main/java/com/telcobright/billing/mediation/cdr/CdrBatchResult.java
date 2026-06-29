package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.cdr;

import java.math.BigDecimal;
import java.util.List;

/**
 * The batch outcome: the qualified calls (with chargeables, written to {@code cdr}), the rejected
 * cdrs (each with its {@code ErrorCode}, written to {@code cdrerror}), and the rows written per table.
 * {@code TotalCharged} sums the customer billed amounts.
 */
public record CdrBatchResult(
        List<RatedCdr> Rated, List<cdr> Errored,
        int CdrsWritten, int CdrErrorsWritten, int ChargeablesWritten) {

    public int Total() { return Rated.size() + Errored.size(); }

    public BigDecimal TotalCharged() {
        return Rated.stream()
                .map(r -> { var c = r.Customer(); return c != null ? c.BilledAmount : BigDecimal.ZERO; })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
