package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.mediation.engine.models.acc_ledger_summary;
import com.telcobright.billing.mediation.engine.models.acc_transaction;
import com.telcobright.billing.mediation.engine.models.account;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

import java.util.ArrayList;

import static com.telcobright.billing.mediation.sql.MySqlFieldExtensions.ToMySqlField;

/**
 * Emits an {@link SmsAccountingEngine.Plan} through the batch's single-connection executor — the same
 * transaction as the cdr / acc_chargeable / summary_affected writes, so the accounting commits or rolls back
 * with the batch. Rows that were read are written back by key with their FINAL values (they were locked
 * {@code FOR UPDATE} when read, so absolute values are safe); partitioned tables are addressed with their
 * partitioning column so MySQL prunes to one partition.
 */
public final class SmsAccountingWriter {
    private SmsAccountingWriter() {}

    static final String AccountInsertColumns =
            "id,idPartner,accountName,serviceGroup,serviceFamily,product,billableType,uom,Depth,isBillable," +
            "isCustomerAccount,isSupplierAccount,balanceBefore,lastAmount,balanceAfter,lastUpdated," +
            "superviseNegativeBalance,negativeBalanceLimit";

    /** Returns the number of statements' affected rows (inserted + updated). */
    public static int Write(ISqlExecutor sql, SmsAccountingEngine.Plan plan, int segmentSize) {
        int affected = 0;

        if (!plan.NewAccounts().isEmpty()) {
            var values = new ArrayList<StringBuilder>();
            for (account a : plan.NewAccounts())
                values.add(new StringBuilder("(")
                        .append(ToMySqlField(a.id)).append(",")
                        .append(ToMySqlField(a.idPartner)).append(",")
                        .append(ToMySqlField(a.accountName)).append(",")
                        .append(ToMySqlField(a.serviceGroup)).append(",")
                        .append(ToMySqlField(a.serviceFamily)).append(",")
                        .append(ToMySqlField(a.product)).append(",")
                        .append(ToMySqlField(a.billableType)).append(",")
                        .append(ToMySqlField(a.uom)).append(",")
                        .append(ToMySqlField(a.Depth)).append(",")
                        .append(ToMySqlField(a.isBillable)).append(",")
                        .append(ToMySqlField(a.isCustomerAccount)).append(",")
                        .append(ToMySqlField(a.isSupplierAccount)).append(",")
                        .append(ToMySqlField(a.balanceBefore)).append(",")
                        .append(ToMySqlField(a.lastAmount)).append(",")
                        .append(ToMySqlField(a.balanceAfter)).append(",")
                        .append(ToMySqlField(a.lastUpdated)).append(",")
                        .append(ToMySqlField(a.superviseNegativeBalance)).append(",")
                        .append(ToMySqlField(a.negativeBalanceLimit)).append(")"));
            affected += BatchSqlWriter.WriteInsertsInSegments(sql,
                    "insert into account (" + AccountInsertColumns + ") values ", values, segmentSize);
        }

        for (account a : plan.UpdatedAccounts())
            affected += sql.ExecuteNonQuery("update account set balanceBefore=" + ToMySqlField(a.balanceBefore)
                    + ",lastAmount=" + ToMySqlField(a.lastAmount)
                    + ",balanceAfter=" + ToMySqlField(a.balanceAfter)
                    + ",lastUpdated=" + ToMySqlField(a.lastUpdated)
                    + " where id=" + ToMySqlField(a.id));

        if (!plan.Transactions().isEmpty()) {
            var values = new ArrayList<StringBuilder>();
            for (acc_transaction t : plan.Transactions()) values.add(t.GetExtInsertValues());
            affected += BatchSqlWriter.WriteInsertsInSegments(sql,
                    "insert into acc_transaction (" + acc_transaction.ExtInsertColumns + ") values ", values, segmentSize);
        }

        if (!plan.NewLedgerRows().isEmpty()) {
            var values = new ArrayList<StringBuilder>();
            for (acc_ledger_summary l : plan.NewLedgerRows())
                values.add(new StringBuilder("(")
                        .append(ToMySqlField(l.id)).append(",")
                        .append(ToMySqlField(l.idAccount)).append(",")
                        .append(ToMySqlField(l.transactionDate)).append(",")
                        .append(ToMySqlField(l.AMOUNT)).append(")"));
            affected += BatchSqlWriter.WriteInsertsInSegments(sql,
                    "insert into acc_ledger_summary (id,idAccount,transactionDate,AMOUNT) values ", values, segmentSize);
        }

        for (acc_ledger_summary l : plan.UpdatedLedgerRows())
            affected += sql.ExecuteNonQuery("update acc_ledger_summary set AMOUNT=" + ToMySqlField(l.AMOUNT)
                    + " where transactionDate=" + ToMySqlField(l.transactionDate)
                    + " and idAccount=" + ToMySqlField(l.idAccount)
                    + " and id=" + ToMySqlField(l.id));
        return affected;
    }
}
