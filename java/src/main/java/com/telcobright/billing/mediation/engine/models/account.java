// Ported from legacy Models_Mediation/account.cs (MediationModel.account) + the balance step of
// EntityExtensions/account.cs (ExecuteTransaction). Plain POCO of the ledger account a transaction posts to.
package com.telcobright.billing.mediation.engine.models;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class account {
    public long id;
    public Long idParent;
    public String idParentExternal;
    public int idPartner;
    public String accountName;
    public int serviceGroup;
    public int serviceFamily;
    public int product;
    public String billableType;
    public String uom;
    public int Depth;
    public String Lineage;
    public String remark;
    public Integer isBillable;
    public Integer isCustomerAccount;
    public Integer isSupplierAccount;
    public BigDecimal balanceBefore = BigDecimal.ZERO;
    public BigDecimal lastAmount;
    public BigDecimal balanceAfter = BigDecimal.ZERO;
    public LocalDateTime lastUpdated;
    public Integer superviseNegativeBalance;
    public BigDecimal negativeBalanceLimit = BigDecimal.ZERO;

    /**
     * Legacy {@code account.ExecuteTransaction}: stamp the transaction's running balance off this account, then
     * move the account. With negative-balance supervision ON a transaction that would take the balance below
     * the limit is refused (legacy threw {@code NotSupportedException}, failing the whole job).
     */
    public void ExecuteTransaction(acc_transaction transaction, LocalDateTime now) {
        transaction.BalanceBefore = this.balanceAfter;
        transaction.BalanceAfter = this.balanceAfter.add(transaction.amount);
        if (this.superviseNegativeBalance != null && this.superviseNegativeBalance == 1
                && transaction.BalanceAfter.compareTo(this.negativeBalanceLimit) < 0) {
            throw new IllegalStateException("NegativeBalanceSupervision must be off to allow negative balance. "
                    + "Balance after this transaction will exceed negative balance limit=" + this.negativeBalanceLimit
                    + " for account name: " + this.accountName);
        }
        this.balanceBefore = this.balanceAfter;
        this.lastAmount = transaction.amount;
        this.balanceAfter = transaction.BalanceAfter;
        this.lastUpdated = now;
    }
}
