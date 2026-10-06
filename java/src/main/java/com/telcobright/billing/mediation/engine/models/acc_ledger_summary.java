// Ported from legacy Models_Mediation/acc_ledger_summary.cs. One row per (account, day): the sum of that
// account's transaction amounts on that day (legacy LedgerSummaryFactory + AccountingContext.UpdateLedgerSummary).
package com.telcobright.billing.mediation.engine.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class acc_ledger_summary {
    public long id;
    public long idAccount;
    public LocalDateTime transactionDate;
    public BigDecimal AMOUNT = BigDecimal.ZERO;
}
