package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.mediation.cdr.RatedCdr;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.acc_transaction;
import com.telcobright.billing.mediation.engine.models.account;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Legacy SMS accounting for one batch, computed in memory over rows the store has LOCKED — the port of the
 * legacy chain {@code CdrPostingAccountingFinder → AccountFactory → SfA2ZWithVatTax.GetTransaction →
 * AccountingContext.ExecuteTransaction (account.ExecuteTransaction + UpdateLedgerSummary)}:
 * <ol>
 * <li><b>Posting account</b> per customer chargeable: postpaid rule → {@code CreateOrGetBillable}
 *   ({@code d0/sg20/p<partner>/sf10/pd0/billable/uom<cur>}, isBillable=1, isCustomerAccount=1); prepaid rule →
 *   {@code CreateOrGetCustomerAccount} ({@code …/customer/…}, isCustomerAccount=1). Missing accounts are created
 *   with an id from the counter and zero balances.</li>
 * <li><b>Transaction</b>: {@code amount = −BilledAmount}, {@code debitOrCredit='c'}, {@code isBillable=1} for the
 *   customer direction, {@code isPrepaid/isBilled = 1} only under a prepaid rule; identity copied from the
 *   chargeable ({@code uniqueBillId}, {@code idEvent}, {@code transactionTime}, {@code description},
 *   {@code glAccountId}, {@code uomId=idBilledUom}).</li>
 * <li><b>Balance</b>: applied IN BATCH ORDER so each transaction's {@code BalanceBefore/After} chains off the
 *   previous one on the same account.</li>
 * <li><b>Ledger</b>: {@code acc_ledger_summary(idAccount, day).AMOUNT += amount}; a new (account, day) gets a
 *   counter id.</li>
 * <li><b>cdr totals</b>: {@code ChargeableMetaTotal = Σ BilledAmount}, {@code TransactionMetaTotal = Σ amount},
 *   {@code SummaryMetaTotal = Σ actualduration} over the cdr's summary rows (day + hour).</li>
 * </ol>
 * The result is a write plan; {@link SmsAccountingWriter} emits it through the batch's executor.
 */
public final class SmsAccountingEngine {
    private SmsAccountingEngine() {}

    /** The batch's accounting effects: what to insert/update. */
    public record Plan(
            List<account> NewAccounts,
            List<account> UpdatedAccounts,
            List<acc_transaction> Transactions,
            List<acc_ledger_summary> NewLedgerRows,
            List<acc_ledger_summary> UpdatedLedgerRows) {}

    /** Number of summary rows each SG20 call folds into ({@code sum_voice_day_01} + {@code sum_voice_hr_01}). */
    static final int SummaryTablesPerCall = 2;

    public static Plan Post(List<RatedCdr> rated, SmsBillingRuleCatalog catalog, ISmsAccountingStore store,
            IAutoIncrementManager ids, LocalDateTime now) {
        // 1. the posting account each customer chargeable resolves to (legacy account-name key).
        var postings = new ArrayList<Posting>();
        var names = new LinkedHashSet<String>();
        for (RatedCdr r : rated)
            for (acc_chargeable c : r.Chargeables()) {
                if (!IsCustomerLeg(c)) continue;
                SmsBillingRuleCatalog.BillingRule rule = catalog.Rule(c.idBillingrule);
                if (rule == null)
                    throw new IllegalStateException("Billing rule " + c.idBillingrule + " not found for uniqueBillId="
                            + c.uniqueBillId);
                account template = PostingAccountTemplate(r.Cdr(), c, rule);
                postings.add(new Posting(r, c, rule, template.accountName, template));
                names.add(template.accountName);
            }

        // 2. lock the existing accounts; create the missing ones (legacy CreateAccountThroughCache).
        Map<String, account> accounts = new HashMap<>(names.isEmpty() ? Map.of() : store.LockAccountsByName(names));
        var newAccounts = new ArrayList<account>();
        for (Posting p : postings)
            if (!accounts.containsKey(p.accountName())) {
                account a = p.template();
                a.id = ids.GetNewCounter("account");
                a.balanceBefore = BigDecimal.ZERO;
                a.balanceAfter = BigDecimal.ZERO;
                a.lastAmount = null;
                a.negativeBalanceLimit = BigDecimal.ZERO;
                a.lastUpdated = now;
                accounts.put(a.accountName, a);
                newAccounts.add(a);
            }

        // 3. transactions + balances, in batch order.
        var transactions = new ArrayList<acc_transaction>();
        var touched = new LinkedHashMap<Long, account>();
        var cdrTotals = new IdentityHashMap<cdr, BigDecimal[]>();   // per cdr OBJECT
        for (Posting p : postings) {
            account acc = accounts.get(p.accountName());
            acc_chargeable c = p.chargeable();
            c.glAccountId = acc.id;

            acc_transaction t = BuildTransaction(c, p.rule(), ids.GetNewCounter("acc_transaction"));
            acc.ExecuteTransaction(t, now);
            transactions.add(t);
            touched.put(acc.id, acc);

            BigDecimal[] totals = cdrTotals.computeIfAbsent(p.rated().Cdr(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            totals[1] = totals[1].add(t.amount);
        }

        // 4. ledger: (account, day) AMOUNT += transaction amount.
        var ledgerPlan = MergeLedger(transactions, store, ids);

        // 5. cdr meta totals (legacy CdrProcessor: chargeable sum, transaction sum, summary actualduration sum).
        for (RatedCdr r : rated) {
            if (r.Chargeables().isEmpty()) continue;
            BigDecimal chargeableTotal = BigDecimal.ZERO;
            for (acc_chargeable c : r.Chargeables())
                chargeableTotal = chargeableTotal.add(c.BilledAmount != null ? c.BilledAmount : BigDecimal.ZERO);
            r.Cdr().ChargeableMetaTotal = chargeableTotal;
            BigDecimal[] totals = cdrTotals.get(r.Cdr());
            r.Cdr().TransactionMetaTotal = totals != null ? totals[1] : BigDecimal.ZERO;
            BigDecimal actual = r.Cdr().DurationSec != null ? r.Cdr().DurationSec : BigDecimal.ZERO;
            r.Cdr().SummaryMetaTotal = actual.multiply(BigDecimal.valueOf(SummaryTablesPerCall));
        }

        var updated = new ArrayList<account>();
        for (account a : touched.values()) if (!newAccounts.contains(a)) updated.add(a);
        return new Plan(newAccounts, updated, transactions, ledgerPlan.created(), ledgerPlan.updated());
    }

    /** Legacy {@code AccountFactory.CreateOrGetBillable / CreateOrGetCustomerAccount} for the customer direction. */
    static account PostingAccountTemplate(cdr cdr, acc_chargeable c, SmsBillingRuleCatalog.BillingRule rule) {
        int idPartner = cdr.InPartnerId != null ? cdr.InPartnerId : 0;
        if (idPartner <= 0) throw new IllegalStateException("customer posting needs InPartnerId > 0 (uniqueBillId=" + c.uniqueBillId + ")");
        if (c.idBilledUom == null || c.idBilledUom.isBlank())
            throw new IllegalStateException("customer posting needs the rate plan currency (uniqueBillId=" + c.uniqueBillId + ")");

        account a = new account();
        a.idPartner = idPartner;
        a.uom = c.idBilledUom;
        a.serviceGroup = c.servicegroup;
        a.serviceFamily = c.servicefamily;
        a.product = SmsOutgoing.AccountProduct;
        a.Depth = SmsOutgoing.AccountDepth;
        if (rule.IsPrepaid()) {
            a.billableType = "/customer";
            a.isCustomerAccount = 1;
        } else {
            a.billableType = "/billable";
            a.isBillable = 1;
            a.isCustomerAccount = 1;
        }
        a.accountName = AccountName(a.Depth, a.serviceGroup, a.idPartner, a.serviceFamily, a.product, a.billableType, a.uom);
        return a;
    }

    /** Legacy account-name key: {@code d<depth>/sg<sg>/p<partner>/sf<sf>/pd<product>/<type>/uom<uom>}. */
    public static String AccountName(int depth, int sg, int idPartner, int sf, int product, String billableType, String uom) {
        return "d" + depth + "/sg" + sg + "/p" + idPartner + "/sf" + sf + "/pd" + (product > 0 ? product : 0)
                + billableType + "/uom" + uom;
    }

    /** Legacy {@code SfA2ZWithVatTax.GetTransaction}. */
    static acc_transaction BuildTransaction(acc_chargeable c, SmsBillingRuleCatalog.BillingRule rule, long id) {
        acc_transaction t = new acc_transaction();
        t.id = id;
        t.transactionTime = c.transactionTime;
        t.debitOrCredit = "c";                       // single entry: "d" top-up, "c" charging
        t.idEvent = c.idEvent;
        t.uniqueBillId = c.uniqueBillId;
        t.description = c.description;
        t.glAccountId = c.glAccountId;
        t.amount = (c.BilledAmount != null ? c.BilledAmount : BigDecimal.ZERO).negate();
        t.uomId = c.idBilledUom;
        t.isBillable = IsCustomerLeg(c) ? 1 : null;
        t.isBilled = rule.IsPrepaid() ? 1 : null;
        t.isPrepaid = rule.IsPrepaid() ? 1 : null;
        return t;
    }

    private record LedgerPlan(List<acc_ledger_summary> created, List<acc_ledger_summary> updated) {}

    private static LedgerPlan MergeLedger(List<acc_transaction> transactions, ISmsAccountingStore store,
            IAutoIncrementManager ids) {
        if (transactions.isEmpty()) return new LedgerPlan(List.of(), List.of());
        var accountIds = new LinkedHashSet<Long>();
        var dates = new LinkedHashSet<LocalDateTime>();
        for (acc_transaction t : transactions) {
            accountIds.add(t.glAccountId);
            dates.add(t.transactionTime.toLocalDate().atStartOfDay());
        }
        // Existing rows, locked. Legacy keyed the cache by (idAccount, transactionDate); if a key ever holds two
        // rows (no unique key exists on the table) the LOWEST id is the one merged into, deterministically.
        var existing = new HashMap<LedgerKey, acc_ledger_summary>();
        var rows = new ArrayList<>(store.LockLedgerRows(accountIds, dates));
        rows.sort(Comparator.comparingLong(r -> r.id));
        for (acc_ledger_summary r : rows)
            existing.putIfAbsent(new LedgerKey(r.idAccount, r.transactionDate), r);

        var created = new LinkedHashMap<LedgerKey, acc_ledger_summary>();
        var updated = new LinkedHashMap<LedgerKey, acc_ledger_summary>();
        for (acc_transaction t : transactions) {
            var key = new LedgerKey(t.glAccountId, t.transactionTime.toLocalDate().atStartOfDay());
            acc_ledger_summary row = existing.get(key);
            if (row != null) {
                updated.put(key, row);
            } else {
                row = created.get(key);
                if (row == null) {
                    row = new acc_ledger_summary();
                    row.id = ids.GetNewCounter("acc_ledger_summary");
                    row.idAccount = key.idAccount();
                    row.transactionDate = key.transactionDate();
                    row.AMOUNT = BigDecimal.ZERO;
                    created.put(key, row);
                }
            }
            row.AMOUNT = row.AMOUNT.add(t.amount);
        }
        return new LedgerPlan(new ArrayList<>(created.values()), new ArrayList<>(updated.values()));
    }

    private record LedgerKey(long idAccount, LocalDateTime transactionDate) {
        LedgerKey { Objects.requireNonNull(transactionDate); }
    }

    private record Posting(RatedCdr rated, acc_chargeable chargeable, SmsBillingRuleCatalog.BillingRule rule,
            String accountName, account template) {}

    static boolean IsCustomerLeg(acc_chargeable c) {
        return c.assignedDirection != null && c.assignedDirection == (byte) AssignmentDirection.Customer.value;
    }
}
