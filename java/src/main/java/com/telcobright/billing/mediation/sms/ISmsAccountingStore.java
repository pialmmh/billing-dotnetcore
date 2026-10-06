package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.account;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The READ side SMS accounting needs inside the batch transaction (the writes go through the batch's
 * {@code ISqlExecutor} like every other row). The live implementation runs on the batch's own connection and
 * locks what it reads ({@code FOR UPDATE}), so a balance is never computed off a row another writer is
 * changing; tests use an in-memory fake.
 */
public interface ISmsAccountingStore {

    /** {@code billingruleassignment} + {@code jsonbillingrule}, the legacy billing-rule source. */
    SmsBillingRuleCatalog LoadBillingRuleCatalog();

    /** Existing accounts by {@code accountName}, LOCKED for update. Absent names are simply missing. */
    Map<String, account> LockAccountsByName(Collection<String> accountNames);

    /** Existing {@code acc_ledger_summary} rows for the (account, day) keys, LOCKED for update. */
    List<acc_ledger_summary> LockLedgerRows(Collection<Long> accountIds, Collection<LocalDateTime> transactionDates);
}
