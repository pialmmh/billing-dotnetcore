// Ported from legacy Models_Mediation/acc_transaction.cs (MediationModel.acc_transaction) + the insert column
// list of its Crud extension. The single-entry ledger posting a chargeable produces (legacy GetTransaction).
package com.telcobright.billing.mediation.engine.models;

import com.telcobright.billing.mediation.sql.MySqlFieldExtensions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class acc_transaction {
    public long id;
    public LocalDateTime transactionTime;
    public Integer seqId;
    public String debitOrCredit;
    public long idEvent;
    public String uniqueBillId;
    public String description;
    public long glAccountId;
    public String uomId;
    public BigDecimal amount;
    public BigDecimal BalanceBefore;
    public BigDecimal BalanceAfter;
    public Integer isBillable;
    public Integer isPrepaid;
    public Integer isBilled;
    public Integer cancelled;
    public Long createdByJob;
    public Long changedByJob;
    public String jsonDetail;

    public static final String ExtInsertColumns =
            "id,transactionTime,seqId,debitOrCredit,idEvent,uniqueBillId,description,glAccountId,uomId,amount," +
            "BalanceBefore,BalanceAfter,isBillable,isPrepaid,isBilled,cancelled,createdByJob,changedByJob,jsonDetail";

    public StringBuilder GetExtInsertValues() {
        return new StringBuilder("(")
                .append(MySqlFieldExtensions.ToMySqlField(this.id)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.transactionTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.seqId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.debitOrCredit)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.idEvent)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.uniqueBillId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.description)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.glAccountId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.uomId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.amount)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.BalanceBefore)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.BalanceAfter)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.isBillable)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.isPrepaid)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.isBilled)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.cancelled)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.createdByJob)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.changedByJob)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.jsonDetail)).append(")");
    }
}
