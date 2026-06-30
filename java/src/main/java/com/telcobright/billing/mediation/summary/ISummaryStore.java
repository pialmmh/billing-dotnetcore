package com.telcobright.billing.mediation.summary;

import com.telcobright.billing.mediation.sql.ISqlExecutor;

/**
 * The batch's tx-bound SQL executor seam (was the summary store; summaries are now outbox-only). One
 * implementation wraps the single per-call MySqlConnection; tests use an in-memory fake. Keeping the DB
 * behind this one interface is what makes the cdr / chargeable / summary-outbox writes testable without a
 * live database — only the live implementation needs the DB go-ahead.
 */
public interface ISummaryStore extends ISqlExecutor {
}
