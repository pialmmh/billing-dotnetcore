// Ported VERBATIM from legacy Models_Mediation/EntityExtensions/Crud/acc_chargeable.cs — the ICacheble<>
// per-row SQL builders (32 cols), plus the insert-column header from _StaticExtInsertColumnHeaders.cs. The
// chargeable rides the same single-connection ISqlExecutor write path as the summary (legacy ProcessChargeables).
#nullable disable
using System;
using System.Text;
using LibraryExtensions;

namespace MediationModel
{
    public partial class acc_chargeable : ICacheble<acc_chargeable>
    {
        // The legacy _StaticExtInsertColumnHeaders.acc_chargeable column list (between the parens).
        public const string ExtInsertColumns =
            "id,uniqueBillId,idEvent,transactionTime,assignedDirection,description,glAccountId,servicegroup," +
            "servicefamily,ProductId,idBilledUom,idQuantityUom,BilledAmount,Quantity,unitPriceOrCharge,Prefix," +
            "RateId,TaxAmount1,TaxAmount2,TaxAmount3,VatAmount1,VatAmount2,VatAmount3,OtherAmount1,OtherAmount2," +
            "OtherAmount3,OtherDecAmount1,OtherDecAmount2,OtherDecAmount3,createdByJob,changedByJob,idBillingrule,jsonDetail";

        public StringBuilder GetExtInsertValues()
        {
            return new StringBuilder("(")
                .Append(this.id.ToMySqlField()).Append(",")
                .Append(this.uniqueBillId.ToMySqlField()).Append(",")
                .Append(this.idEvent.ToMySqlField()).Append(",")
                .Append(this.transactionTime.ToMySqlField()).Append(",")
                .Append(this.assignedDirection.ToMySqlField()).Append(",")
                .Append(this.description.ToMySqlField()).Append(",")
                .Append(this.glAccountId.ToMySqlField()).Append(",")
                .Append(this.servicegroup.ToMySqlField()).Append(",")
                .Append(this.servicefamily.ToMySqlField()).Append(",")
                .Append(this.ProductId.ToMySqlField()).Append(",")
                .Append(this.idBilledUom.ToMySqlField()).Append(",")
                .Append(this.idQuantityUom.ToMySqlField()).Append(",")
                .Append(this.BilledAmount.ToMySqlField()).Append(",")
                .Append(this.Quantity.ToMySqlField()).Append(",")
                .Append(this.unitPriceOrCharge.ToMySqlField()).Append(",")
                .Append(this.Prefix.ToMySqlField()).Append(",")
                .Append(this.RateId.ToMySqlField()).Append(",")
                .Append(this.TaxAmount1.ToMySqlField()).Append(",")
                .Append(this.TaxAmount2.ToMySqlField()).Append(",")
                .Append(this.TaxAmount3.ToMySqlField()).Append(",")
                .Append(this.VatAmount1.ToMySqlField()).Append(",")
                .Append(this.VatAmount2.ToMySqlField()).Append(",")
                .Append(this.VatAmount3.ToMySqlField()).Append(",")
                .Append(this.OtherAmount1.ToMySqlField()).Append(",")
                .Append(this.OtherAmount2.ToMySqlField()).Append(",")
                .Append(this.OtherAmount3.ToMySqlField()).Append(",")
                .Append(this.OtherDecAmount1.ToMySqlField()).Append(",")
                .Append(this.OtherDecAmount2.ToMySqlField()).Append(",")
                .Append(this.OtherDecAmount3.ToMySqlField()).Append(",")
                .Append(this.createdByJob.ToMySqlField()).Append(",")
                .Append(this.changedByJob.ToMySqlField()).Append(",")
                .Append(this.idBillingrule.ToMySqlField()).Append(",")
                .Append(this.jsonDetail.ToMySqlField()).Append(")");
        }

        public StringBuilder GetUpdateCommand(Func<acc_chargeable, string> whereClauseMethod)
        {
            return new StringBuilder("update acc_chargeable set ")
                .Append("id=").Append(this.id.ToMySqlField()).Append(",")
                .Append("uniqueBillId=").Append(this.uniqueBillId.ToMySqlField()).Append(",")
                .Append("idEvent=").Append(this.idEvent.ToMySqlField()).Append(",")
                .Append("transactionTime=").Append(this.transactionTime.ToMySqlField()).Append(",")
                .Append("assignedDirection=").Append(this.assignedDirection.ToMySqlField()).Append(",")
                .Append("description=").Append(this.description.ToMySqlField()).Append(",")
                .Append("glAccountId=").Append(this.glAccountId.ToMySqlField()).Append(",")
                .Append("servicegroup=").Append(this.servicegroup.ToMySqlField()).Append(",")
                .Append("servicefamily=").Append(this.servicefamily.ToMySqlField()).Append(",")
                .Append("ProductId=").Append(this.ProductId.ToMySqlField()).Append(",")
                .Append("idBilledUom=").Append(this.idBilledUom.ToMySqlField()).Append(",")
                .Append("idQuantityUom=").Append(this.idQuantityUom.ToMySqlField()).Append(",")
                .Append("BilledAmount=").Append(this.BilledAmount.ToMySqlField()).Append(",")
                .Append("Quantity=").Append(this.Quantity.ToMySqlField()).Append(",")
                .Append("unitPriceOrCharge=").Append(this.unitPriceOrCharge.ToMySqlField()).Append(",")
                .Append("Prefix=").Append(this.Prefix.ToMySqlField()).Append(",")
                .Append("RateId=").Append(this.RateId.ToMySqlField()).Append(",")
                .Append("TaxAmount1=").Append(this.TaxAmount1.ToMySqlField()).Append(",")
                .Append("TaxAmount2=").Append(this.TaxAmount2.ToMySqlField()).Append(",")
                .Append("TaxAmount3=").Append(this.TaxAmount3.ToMySqlField()).Append(",")
                .Append("VatAmount1=").Append(this.VatAmount1.ToMySqlField()).Append(",")
                .Append("VatAmount2=").Append(this.VatAmount2.ToMySqlField()).Append(",")
                .Append("VatAmount3=").Append(this.VatAmount3.ToMySqlField()).Append(",")
                .Append("OtherAmount1=").Append(this.OtherAmount1.ToMySqlField()).Append(",")
                .Append("OtherAmount2=").Append(this.OtherAmount2.ToMySqlField()).Append(",")
                .Append("OtherAmount3=").Append(this.OtherAmount3.ToMySqlField()).Append(",")
                .Append("OtherDecAmount1=").Append(this.OtherDecAmount1.ToMySqlField()).Append(",")
                .Append("OtherDecAmount2=").Append(this.OtherDecAmount2.ToMySqlField()).Append(",")
                .Append("OtherDecAmount3=").Append(this.OtherDecAmount3.ToMySqlField()).Append(",")
                .Append("createdByJob=").Append(this.createdByJob.ToMySqlField()).Append(",")
                .Append("changedByJob=").Append(this.changedByJob.ToMySqlField()).Append(",")
                .Append("idBillingrule=").Append(this.idBillingrule.ToMySqlField()).Append(",")
                .Append("jsonDetail=").Append(this.jsonDetail.ToMySqlField())
                .Append(whereClauseMethod.Invoke(this));
        }

        public StringBuilder GetDeleteCommand(Func<acc_chargeable, string> whereClauseMethod)
        {
            return new StringBuilder("delete from acc_chargeable ").Append(whereClauseMethod.Invoke(this));
        }
    }
}
