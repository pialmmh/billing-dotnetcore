// Ported VERBATIM from legacy Models_Mediation/acc_chargeable.cs (MediationModel.acc_chargeable) MERGED
// with Models_Mediation/EntityExtensions/Crud/acc_chargeable.cs (the ICacheble<> per-row SQL builders,
// 32 cols, plus the insert-column header from _StaticExtInsertColumnHeaders.cs).
// Plain POCO — the chargeable a service family produces. Keyed in CdrExt.Chargeables by (sg, sf, dir).
// The chargeable rides the same single-connection ISqlExecutor write path as the summary.
package com.telcobright.billing.mediation.engine.models;

import com.telcobright.billing.mediation.summary.cache.ICacheble;
import com.telcobright.billing.mediation.summary.cache.MySqlFieldExtensions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Function;

public class acc_chargeable implements ICacheble<acc_chargeable> {
    public long id;
    public String uniqueBillId;
    public long idEvent;
    public LocalDateTime transactionTime;
    public Byte assignedDirection;
    public String description;
    public long glAccountId;
    public int servicegroup;
    public int servicefamily;
    public long ProductId;
    public String idBilledUom;
    public String idQuantityUom;
    public BigDecimal BilledAmount;
    public BigDecimal Quantity;
    public BigDecimal unitPriceOrCharge;
    public String Prefix;
    public long RateId;
    public BigDecimal TaxAmount1;
    public BigDecimal TaxAmount2;
    public BigDecimal TaxAmount3;
    public BigDecimal VatAmount1;
    public BigDecimal VatAmount2;
    public BigDecimal VatAmount3;
    public BigDecimal OtherAmount1;
    public BigDecimal OtherAmount2;
    public BigDecimal OtherAmount3;
    public BigDecimal OtherDecAmount1;
    public BigDecimal OtherDecAmount2;
    public BigDecimal OtherDecAmount3;
    public Long createdByJob;
    public Long changedByJob;
    public int idBillingrule;
    public String jsonDetail;

    // The legacy _StaticExtInsertColumnHeaders.acc_chargeable column list (between the parens).
    public static final String ExtInsertColumns =
            "id,uniqueBillId,idEvent,transactionTime,assignedDirection,description,glAccountId,servicegroup," +
            "servicefamily,ProductId,idBilledUom,idQuantityUom,BilledAmount,Quantity,unitPriceOrCharge,Prefix," +
            "RateId,TaxAmount1,TaxAmount2,TaxAmount3,VatAmount1,VatAmount2,VatAmount3,OtherAmount1,OtherAmount2," +
            "OtherAmount3,OtherDecAmount1,OtherDecAmount2,OtherDecAmount3,createdByJob,changedByJob,idBillingrule,jsonDetail";

    @Override
    public StringBuilder GetExtInsertValues() {
        return new StringBuilder("(")
                .append(MySqlFieldExtensions.ToMySqlField(this.id)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.uniqueBillId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.idEvent)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.transactionTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.assignedDirection)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.description)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.glAccountId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.servicegroup)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.servicefamily)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ProductId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.idBilledUom)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.idQuantityUom)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.BilledAmount)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Quantity)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.unitPriceOrCharge)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Prefix)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RateId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.VatAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.VatAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.VatAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.createdByJob)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.changedByJob)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.idBillingrule)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.jsonDetail)).append(")");
    }

    @Override
    public StringBuilder GetUpdateCommand(Function<acc_chargeable, String> whereClauseMethod) {
        return new StringBuilder("update acc_chargeable set ")
                .append("id=").append(MySqlFieldExtensions.ToMySqlField(this.id)).append(",")
                .append("uniqueBillId=").append(MySqlFieldExtensions.ToMySqlField(this.uniqueBillId)).append(",")
                .append("idEvent=").append(MySqlFieldExtensions.ToMySqlField(this.idEvent)).append(",")
                .append("transactionTime=").append(MySqlFieldExtensions.ToMySqlField(this.transactionTime)).append(",")
                .append("assignedDirection=").append(MySqlFieldExtensions.ToMySqlField(this.assignedDirection)).append(",")
                .append("description=").append(MySqlFieldExtensions.ToMySqlField(this.description)).append(",")
                .append("glAccountId=").append(MySqlFieldExtensions.ToMySqlField(this.glAccountId)).append(",")
                .append("servicegroup=").append(MySqlFieldExtensions.ToMySqlField(this.servicegroup)).append(",")
                .append("servicefamily=").append(MySqlFieldExtensions.ToMySqlField(this.servicefamily)).append(",")
                .append("ProductId=").append(MySqlFieldExtensions.ToMySqlField(this.ProductId)).append(",")
                .append("idBilledUom=").append(MySqlFieldExtensions.ToMySqlField(this.idBilledUom)).append(",")
                .append("idQuantityUom=").append(MySqlFieldExtensions.ToMySqlField(this.idQuantityUom)).append(",")
                .append("BilledAmount=").append(MySqlFieldExtensions.ToMySqlField(this.BilledAmount)).append(",")
                .append("Quantity=").append(MySqlFieldExtensions.ToMySqlField(this.Quantity)).append(",")
                .append("unitPriceOrCharge=").append(MySqlFieldExtensions.ToMySqlField(this.unitPriceOrCharge)).append(",")
                .append("Prefix=").append(MySqlFieldExtensions.ToMySqlField(this.Prefix)).append(",")
                .append("RateId=").append(MySqlFieldExtensions.ToMySqlField(this.RateId)).append(",")
                .append("TaxAmount1=").append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount1)).append(",")
                .append("TaxAmount2=").append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount2)).append(",")
                .append("TaxAmount3=").append(MySqlFieldExtensions.ToMySqlField(this.TaxAmount3)).append(",")
                .append("VatAmount1=").append(MySqlFieldExtensions.ToMySqlField(this.VatAmount1)).append(",")
                .append("VatAmount2=").append(MySqlFieldExtensions.ToMySqlField(this.VatAmount2)).append(",")
                .append("VatAmount3=").append(MySqlFieldExtensions.ToMySqlField(this.VatAmount3)).append(",")
                .append("OtherAmount1=").append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount1)).append(",")
                .append("OtherAmount2=").append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount2)).append(",")
                .append("OtherAmount3=").append(MySqlFieldExtensions.ToMySqlField(this.OtherAmount3)).append(",")
                .append("OtherDecAmount1=").append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount1)).append(",")
                .append("OtherDecAmount2=").append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount2)).append(",")
                .append("OtherDecAmount3=").append(MySqlFieldExtensions.ToMySqlField(this.OtherDecAmount3)).append(",")
                .append("createdByJob=").append(MySqlFieldExtensions.ToMySqlField(this.createdByJob)).append(",")
                .append("changedByJob=").append(MySqlFieldExtensions.ToMySqlField(this.changedByJob)).append(",")
                .append("idBillingrule=").append(MySqlFieldExtensions.ToMySqlField(this.idBillingrule)).append(",")
                .append("jsonDetail=").append(MySqlFieldExtensions.ToMySqlField(this.jsonDetail))
                .append(whereClauseMethod.apply(this));
    }

    @Override
    public StringBuilder GetDeleteCommand(Function<acc_chargeable, String> whereClauseMethod) {
        return new StringBuilder("delete from acc_chargeable ").append(whereClauseMethod.apply(this));
    }
}
