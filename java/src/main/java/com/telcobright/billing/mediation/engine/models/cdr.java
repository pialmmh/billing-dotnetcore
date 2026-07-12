// Ported VERBATIM from legacy Models_Mediation/cdr.cs (MediationModel.cdr) MERGED with
// Models_Mediation/EntityExtensions/Crud/cdr.cs — the ICacheble<cdr> per-row SQL builders (104 cols,
// exact legacy order), plus the insert-column header from _StaticExtInsertColumnHeaders.cs.
// Plain POCO — no EF/framework deps. The cdr write rides the same single-connection segmented write path.
package com.telcobright.billing.mediation.engine.models;

import com.telcobright.billing.mediation.sql.ICacheble;
import com.telcobright.billing.mediation.sql.MySqlFieldExtensions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Function;

public class cdr implements ICacheble<cdr> {
    public int SwitchId;
    public long IdCall;
    public long SequenceNumber;
    public String FileName;
    public int ServiceGroup;
    public String IncomingRoute;
    public String OriginatingIP;
    public Integer OPC;
    public Integer OriginatingCIC;
    public String OriginatingCalledNumber;
    public String TerminatingCalledNumber;
    public String OriginatingCallingNumber;
    public String TerminatingCallingNumber;
    public Integer PrePaid;
    public BigDecimal DurationSec;
    public LocalDateTime EndTime = LocalDateTime.of(1, 1, 1, 0, 0);
    public LocalDateTime ConnectTime;
    public LocalDateTime AnswerTime;
    public Integer ChargingStatus;
    public Float PDD;
    public String CountryCode;
    public String AreaCodeOrLata;
    public Integer ReleaseDirection;
    public Integer ReleaseCauseSystem;
    public Integer ReleaseCauseEgress;
    public String OutgoingRoute;
    public String TerminatingIP;
    public Integer DPC;
    public Integer TerminatingCIC;
    public LocalDateTime StartTime = LocalDateTime.of(1, 1, 1, 0, 0);
    public Integer InPartnerId;
    public BigDecimal CustomerRate;
    public Integer OutPartnerId;
    public BigDecimal SupplierRate;
    public String MatchedPrefixY;
    public BigDecimal UsdRateY;
    public String MatchedPrefixCustomer;
    public String MatchedPrefixSupplier;
    public BigDecimal InPartnerCost;
    public BigDecimal OutPartnerCost;
    public BigDecimal CostAnsIn;
    public BigDecimal CostIcxIn;
    public BigDecimal Tax1;
    public BigDecimal IgwRevenueIn;
    public BigDecimal RevenueAnsOut;
    public BigDecimal RevenueIgwOut;
    public BigDecimal RevenueIcxOut;
    public BigDecimal Tax2;
    public BigDecimal XAmount;
    public BigDecimal YAmount;
    public String AnsPrefixOrig;
    public Integer AnsIdOrig;
    public String AnsPrefixTerm;
    public Integer AnsIdTerm;
    public Integer ValidFlag;
    public Integer PartialFlag;
    public Integer ReleaseCauseIngress;
    public Integer InRoamingOpId;
    public Integer OutRoamingOpId;
    public Integer CalledPartyNOA;
    public Integer CallingPartyNOA;
    public String AdditionalSystemCodes;
    public String AdditionalPartyNumber;
    public String ResellerIds;
    public BigDecimal ZAmount;
    public String PreviousRoutes;
    public Integer E1Id;
    public String MediaIp1;
    public String MediaIp2;
    public String MediaIp3;
    public String MediaIp4;
    public Float CallReleaseDuration;
    public Integer E1IdOut;
    public String InTrunkAdditionalInfo;
    public String OutTrunkAdditionalInfo;
    public String InMgwId;
    public String OutMgwId;
    public Integer MediationComplete;
    public String Codec;
    public Integer ConnectedNumberType;
    public String RedirectingNumber;
    public Integer CallForwardOrRoamingType;
    public LocalDateTime OtherDate;
    public BigDecimal SummaryMetaTotal;
    public BigDecimal TransactionMetaTotal;
    public BigDecimal ChargeableMetaTotal;
    public String ErrorCode;
    public Integer NERSuccess;
    public BigDecimal RoundedDuration;
    public BigDecimal PartialDuration;
    public LocalDateTime PartialAnswerTime;
    public LocalDateTime PartialEndTime;
    public Long FinalRecord;
    public BigDecimal Duration1;
    public BigDecimal Duration2;
    public BigDecimal Duration3;
    public BigDecimal Duration4;
    public Integer PreviousPeriodCdr;
    public String UniqueBillId;
    public String AdditionalMetaData;
    public Integer Category;
    public Integer SubCategory;
    public Long ChangedByJobId;
    public LocalDateTime SignalingStartTime = LocalDateTime.of(1, 1, 1, 0, 0);

    // --- NEW columns carried by the Kafka `cdr_rated` ingest (cdr-kafka-ingest-contract §2) ---
    // These EXTEND the legacy engine model ("extend, don't replace"); they are NOT part of the legacy
    // 104-col ExtInsertColumns order below. They are populated by CdrEventPreprocessor. The write-layer
    // wiring (append to ExtInsertColumns/GetExtInsertValues + DDL) is a SEPARATE staged change — until then
    // these are in-memory only (mapped + carried through the pipeline, not yet persisted to their own columns).
    public String ResellerHierarchy;
    public String ChannelCallUuid;
    public String HangupCause;
    public String InPartnerUom;
    public Long IdPackageAccount;
    public BigDecimal PackageAmount;

    // _StaticExtInsertColumnHeaders.cdr column list (between the parens), exact order.
    public static final String ExtInsertColumns =
            "SwitchId,IdCall,SequenceNumber,FileName,ServiceGroup,IncomingRoute,OriginatingIP,OPC,OriginatingCIC," +
            "OriginatingCalledNumber,TerminatingCalledNumber,OriginatingCallingNumber,TerminatingCallingNumber," +
            "PrePaid,DurationSec,EndTime,ConnectTime,AnswerTime,ChargingStatus,PDD,CountryCode,AreaCodeOrLata," +
            "ReleaseDirection,ReleaseCauseSystem,ReleaseCauseEgress,OutgoingRoute,TerminatingIP,DPC,TerminatingCIC," +
            "StartTime,InPartnerId,CustomerRate,OutPartnerId,SupplierRate,MatchedPrefixY,UsdRateY,MatchedPrefixCustomer," +
            "MatchedPrefixSupplier,InPartnerCost,OutPartnerCost,CostAnsIn,CostIcxIn,Tax1,IgwRevenueIn,RevenueAnsOut," +
            "RevenueIgwOut,RevenueIcxOut,Tax2,XAmount,YAmount,AnsPrefixOrig,AnsIdOrig,AnsPrefixTerm,AnsIdTerm,ValidFlag," +
            "PartialFlag,ReleaseCauseIngress,InRoamingOpId,OutRoamingOpId,CalledPartyNOA,CallingPartyNOA," +
            "AdditionalSystemCodes,AdditionalPartyNumber,ResellerIds,ZAmount,PreviousRoutes,E1Id,MediaIp1,MediaIp2," +
            "MediaIp3,MediaIp4,CallReleaseDuration,E1IdOut,InTrunkAdditionalInfo,OutTrunkAdditionalInfo,InMgwId,OutMgwId," +
            "MediationComplete,Codec,ConnectedNumberType,RedirectingNumber,CallForwardOrRoamingType,OtherDate," +
            "SummaryMetaTotal,TransactionMetaTotal,ChargeableMetaTotal,ErrorCode,NERSuccess,RoundedDuration," +
            "PartialDuration,PartialAnswerTime,PartialEndTime,FinalRecord,Duration1,Duration2,Duration3,Duration4," +
            "PreviousPeriodCdr,UniqueBillId,AdditionalMetaData,Category,SubCategory,ChangedByJobId,SignalingStartTime";

    @Override
    public StringBuilder GetExtInsertValues() {
        return new StringBuilder("(")
                .append(MySqlFieldExtensions.ToMySqlField(this.SwitchId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.IdCall)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.SequenceNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.FileName)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ServiceGroup)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.IncomingRoute)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OriginatingIP)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OPC)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCIC)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCalledNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCalledNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCallingNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCallingNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PrePaid)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.DurationSec)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.EndTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ConnectTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AnswerTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ChargingStatus)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PDD)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CountryCode)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AreaCodeOrLata)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ReleaseDirection)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseSystem)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseEgress)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutgoingRoute)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TerminatingIP)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.DPC)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCIC)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.StartTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.InPartnerId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CustomerRate)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutPartnerId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.SupplierRate)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixY)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.UsdRateY)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixCustomer)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixSupplier)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.InPartnerCost)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutPartnerCost)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CostAnsIn)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CostIcxIn)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Tax1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.IgwRevenueIn)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RevenueAnsOut)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RevenueIgwOut)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RevenueIcxOut)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Tax2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.XAmount)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.YAmount)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AnsPrefixOrig)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AnsIdOrig)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AnsPrefixTerm)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AnsIdTerm)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ValidFlag)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PartialFlag)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseIngress)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.InRoamingOpId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutRoamingOpId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CalledPartyNOA)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CallingPartyNOA)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AdditionalSystemCodes)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AdditionalPartyNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ResellerIds)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ZAmount)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PreviousRoutes)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.E1Id)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MediaIp1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MediaIp2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MediaIp3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MediaIp4)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CallReleaseDuration)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.E1IdOut)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.InTrunkAdditionalInfo)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutTrunkAdditionalInfo)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.InMgwId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OutMgwId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.MediationComplete)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Codec)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ConnectedNumberType)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RedirectingNumber)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.CallForwardOrRoamingType)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.OtherDate)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.SummaryMetaTotal)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.TransactionMetaTotal)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ChargeableMetaTotal)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ErrorCode)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.NERSuccess)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.RoundedDuration)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PartialDuration)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PartialAnswerTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PartialEndTime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.FinalRecord)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Duration1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Duration2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Duration3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Duration4)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PreviousPeriodCdr)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.UniqueBillId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.AdditionalMetaData)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.Category)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.SubCategory)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.ChangedByJobId)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.SignalingStartTime)).append(")");
    }

    @Override
    public StringBuilder GetUpdateCommand(Function<cdr, String> whereClauseMethod) {
        return new StringBuilder("update cdr set ")
                .append("SwitchId=").append(MySqlFieldExtensions.ToMySqlField(this.SwitchId)).append(",")
                .append("IdCall=").append(MySqlFieldExtensions.ToMySqlField(this.IdCall)).append(",")
                .append("SequenceNumber=").append(MySqlFieldExtensions.ToMySqlField(this.SequenceNumber)).append(",")
                .append("FileName=").append(MySqlFieldExtensions.ToMySqlField(this.FileName)).append(",")
                .append("ServiceGroup=").append(MySqlFieldExtensions.ToMySqlField(this.ServiceGroup)).append(",")
                .append("IncomingRoute=").append(MySqlFieldExtensions.ToMySqlField(this.IncomingRoute)).append(",")
                .append("OriginatingIP=").append(MySqlFieldExtensions.ToMySqlField(this.OriginatingIP)).append(",")
                .append("OPC=").append(MySqlFieldExtensions.ToMySqlField(this.OPC)).append(",")
                .append("OriginatingCIC=").append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCIC)).append(",")
                .append("OriginatingCalledNumber=").append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCalledNumber)).append(",")
                .append("TerminatingCalledNumber=").append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCalledNumber)).append(",")
                .append("OriginatingCallingNumber=").append(MySqlFieldExtensions.ToMySqlField(this.OriginatingCallingNumber)).append(",")
                .append("TerminatingCallingNumber=").append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCallingNumber)).append(",")
                .append("PrePaid=").append(MySqlFieldExtensions.ToMySqlField(this.PrePaid)).append(",")
                .append("DurationSec=").append(MySqlFieldExtensions.ToMySqlField(this.DurationSec)).append(",")
                .append("EndTime=").append(MySqlFieldExtensions.ToMySqlField(this.EndTime)).append(",")
                .append("ConnectTime=").append(MySqlFieldExtensions.ToMySqlField(this.ConnectTime)).append(",")
                .append("AnswerTime=").append(MySqlFieldExtensions.ToMySqlField(this.AnswerTime)).append(",")
                .append("ChargingStatus=").append(MySqlFieldExtensions.ToMySqlField(this.ChargingStatus)).append(",")
                .append("PDD=").append(MySqlFieldExtensions.ToMySqlField(this.PDD)).append(",")
                .append("CountryCode=").append(MySqlFieldExtensions.ToMySqlField(this.CountryCode)).append(",")
                .append("AreaCodeOrLata=").append(MySqlFieldExtensions.ToMySqlField(this.AreaCodeOrLata)).append(",")
                .append("ReleaseDirection=").append(MySqlFieldExtensions.ToMySqlField(this.ReleaseDirection)).append(",")
                .append("ReleaseCauseSystem=").append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseSystem)).append(",")
                .append("ReleaseCauseEgress=").append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseEgress)).append(",")
                .append("OutgoingRoute=").append(MySqlFieldExtensions.ToMySqlField(this.OutgoingRoute)).append(",")
                .append("TerminatingIP=").append(MySqlFieldExtensions.ToMySqlField(this.TerminatingIP)).append(",")
                .append("DPC=").append(MySqlFieldExtensions.ToMySqlField(this.DPC)).append(",")
                .append("TerminatingCIC=").append(MySqlFieldExtensions.ToMySqlField(this.TerminatingCIC)).append(",")
                .append("StartTime=").append(MySqlFieldExtensions.ToMySqlField(this.StartTime)).append(",")
                .append("InPartnerId=").append(MySqlFieldExtensions.ToMySqlField(this.InPartnerId)).append(",")
                .append("CustomerRate=").append(MySqlFieldExtensions.ToMySqlField(this.CustomerRate)).append(",")
                .append("OutPartnerId=").append(MySqlFieldExtensions.ToMySqlField(this.OutPartnerId)).append(",")
                .append("SupplierRate=").append(MySqlFieldExtensions.ToMySqlField(this.SupplierRate)).append(",")
                .append("MatchedPrefixY=").append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixY)).append(",")
                .append("UsdRateY=").append(MySqlFieldExtensions.ToMySqlField(this.UsdRateY)).append(",")
                .append("MatchedPrefixCustomer=").append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixCustomer)).append(",")
                .append("MatchedPrefixSupplier=").append(MySqlFieldExtensions.ToMySqlField(this.MatchedPrefixSupplier)).append(",")
                .append("InPartnerCost=").append(MySqlFieldExtensions.ToMySqlField(this.InPartnerCost)).append(",")
                .append("OutPartnerCost=").append(MySqlFieldExtensions.ToMySqlField(this.OutPartnerCost)).append(",")
                .append("CostAnsIn=").append(MySqlFieldExtensions.ToMySqlField(this.CostAnsIn)).append(",")
                .append("CostIcxIn=").append(MySqlFieldExtensions.ToMySqlField(this.CostIcxIn)).append(",")
                .append("Tax1=").append(MySqlFieldExtensions.ToMySqlField(this.Tax1)).append(",")
                .append("IgwRevenueIn=").append(MySqlFieldExtensions.ToMySqlField(this.IgwRevenueIn)).append(",")
                .append("RevenueAnsOut=").append(MySqlFieldExtensions.ToMySqlField(this.RevenueAnsOut)).append(",")
                .append("RevenueIgwOut=").append(MySqlFieldExtensions.ToMySqlField(this.RevenueIgwOut)).append(",")
                .append("RevenueIcxOut=").append(MySqlFieldExtensions.ToMySqlField(this.RevenueIcxOut)).append(",")
                .append("Tax2=").append(MySqlFieldExtensions.ToMySqlField(this.Tax2)).append(",")
                .append("XAmount=").append(MySqlFieldExtensions.ToMySqlField(this.XAmount)).append(",")
                .append("YAmount=").append(MySqlFieldExtensions.ToMySqlField(this.YAmount)).append(",")
                .append("AnsPrefixOrig=").append(MySqlFieldExtensions.ToMySqlField(this.AnsPrefixOrig)).append(",")
                .append("AnsIdOrig=").append(MySqlFieldExtensions.ToMySqlField(this.AnsIdOrig)).append(",")
                .append("AnsPrefixTerm=").append(MySqlFieldExtensions.ToMySqlField(this.AnsPrefixTerm)).append(",")
                .append("AnsIdTerm=").append(MySqlFieldExtensions.ToMySqlField(this.AnsIdTerm)).append(",")
                .append("ValidFlag=").append(MySqlFieldExtensions.ToMySqlField(this.ValidFlag)).append(",")
                .append("PartialFlag=").append(MySqlFieldExtensions.ToMySqlField(this.PartialFlag)).append(",")
                .append("ReleaseCauseIngress=").append(MySqlFieldExtensions.ToMySqlField(this.ReleaseCauseIngress)).append(",")
                .append("InRoamingOpId=").append(MySqlFieldExtensions.ToMySqlField(this.InRoamingOpId)).append(",")
                .append("OutRoamingOpId=").append(MySqlFieldExtensions.ToMySqlField(this.OutRoamingOpId)).append(",")
                .append("CalledPartyNOA=").append(MySqlFieldExtensions.ToMySqlField(this.CalledPartyNOA)).append(",")
                .append("CallingPartyNOA=").append(MySqlFieldExtensions.ToMySqlField(this.CallingPartyNOA)).append(",")
                .append("AdditionalSystemCodes=").append(MySqlFieldExtensions.ToMySqlField(this.AdditionalSystemCodes)).append(",")
                .append("AdditionalPartyNumber=").append(MySqlFieldExtensions.ToMySqlField(this.AdditionalPartyNumber)).append(",")
                .append("ResellerIds=").append(MySqlFieldExtensions.ToMySqlField(this.ResellerIds)).append(",")
                .append("ZAmount=").append(MySqlFieldExtensions.ToMySqlField(this.ZAmount)).append(",")
                .append("PreviousRoutes=").append(MySqlFieldExtensions.ToMySqlField(this.PreviousRoutes)).append(",")
                .append("E1Id=").append(MySqlFieldExtensions.ToMySqlField(this.E1Id)).append(",")
                .append("MediaIp1=").append(MySqlFieldExtensions.ToMySqlField(this.MediaIp1)).append(",")
                .append("MediaIp2=").append(MySqlFieldExtensions.ToMySqlField(this.MediaIp2)).append(",")
                .append("MediaIp3=").append(MySqlFieldExtensions.ToMySqlField(this.MediaIp3)).append(",")
                .append("MediaIp4=").append(MySqlFieldExtensions.ToMySqlField(this.MediaIp4)).append(",")
                .append("CallReleaseDuration=").append(MySqlFieldExtensions.ToMySqlField(this.CallReleaseDuration)).append(",")
                .append("E1IdOut=").append(MySqlFieldExtensions.ToMySqlField(this.E1IdOut)).append(",")
                .append("InTrunkAdditionalInfo=").append(MySqlFieldExtensions.ToMySqlField(this.InTrunkAdditionalInfo)).append(",")
                .append("OutTrunkAdditionalInfo=").append(MySqlFieldExtensions.ToMySqlField(this.OutTrunkAdditionalInfo)).append(",")
                .append("InMgwId=").append(MySqlFieldExtensions.ToMySqlField(this.InMgwId)).append(",")
                .append("OutMgwId=").append(MySqlFieldExtensions.ToMySqlField(this.OutMgwId)).append(",")
                .append("MediationComplete=").append(MySqlFieldExtensions.ToMySqlField(this.MediationComplete)).append(",")
                .append("Codec=").append(MySqlFieldExtensions.ToMySqlField(this.Codec)).append(",")
                .append("ConnectedNumberType=").append(MySqlFieldExtensions.ToMySqlField(this.ConnectedNumberType)).append(",")
                .append("RedirectingNumber=").append(MySqlFieldExtensions.ToMySqlField(this.RedirectingNumber)).append(",")
                .append("CallForwardOrRoamingType=").append(MySqlFieldExtensions.ToMySqlField(this.CallForwardOrRoamingType)).append(",")
                .append("OtherDate=").append(MySqlFieldExtensions.ToMySqlField(this.OtherDate)).append(",")
                .append("SummaryMetaTotal=").append(MySqlFieldExtensions.ToMySqlField(this.SummaryMetaTotal)).append(",")
                .append("TransactionMetaTotal=").append(MySqlFieldExtensions.ToMySqlField(this.TransactionMetaTotal)).append(",")
                .append("ChargeableMetaTotal=").append(MySqlFieldExtensions.ToMySqlField(this.ChargeableMetaTotal)).append(",")
                .append("ErrorCode=").append(MySqlFieldExtensions.ToMySqlField(this.ErrorCode)).append(",")
                .append("NERSuccess=").append(MySqlFieldExtensions.ToMySqlField(this.NERSuccess)).append(",")
                .append("RoundedDuration=").append(MySqlFieldExtensions.ToMySqlField(this.RoundedDuration)).append(",")
                .append("PartialDuration=").append(MySqlFieldExtensions.ToMySqlField(this.PartialDuration)).append(",")
                .append("PartialAnswerTime=").append(MySqlFieldExtensions.ToMySqlField(this.PartialAnswerTime)).append(",")
                .append("PartialEndTime=").append(MySqlFieldExtensions.ToMySqlField(this.PartialEndTime)).append(",")
                .append("FinalRecord=").append(MySqlFieldExtensions.ToMySqlField(this.FinalRecord)).append(",")
                .append("Duration1=").append(MySqlFieldExtensions.ToMySqlField(this.Duration1)).append(",")
                .append("Duration2=").append(MySqlFieldExtensions.ToMySqlField(this.Duration2)).append(",")
                .append("Duration3=").append(MySqlFieldExtensions.ToMySqlField(this.Duration3)).append(",")
                .append("Duration4=").append(MySqlFieldExtensions.ToMySqlField(this.Duration4)).append(",")
                .append("PreviousPeriodCdr=").append(MySqlFieldExtensions.ToMySqlField(this.PreviousPeriodCdr)).append(",")
                .append("UniqueBillId=").append(MySqlFieldExtensions.ToMySqlField(this.UniqueBillId)).append(",")
                .append("AdditionalMetaData=").append(MySqlFieldExtensions.ToMySqlField(this.AdditionalMetaData)).append(",")
                .append("Category=").append(MySqlFieldExtensions.ToMySqlField(this.Category)).append(",")
                .append("SubCategory=").append(MySqlFieldExtensions.ToMySqlField(this.SubCategory)).append(",")
                .append("ChangedByJobId=").append(MySqlFieldExtensions.ToMySqlField(this.ChangedByJobId)).append(",")
                .append("SignalingStartTime=").append(MySqlFieldExtensions.ToMySqlField(this.SignalingStartTime))
                .append(whereClauseMethod.apply(this));
    }

    @Override
    public StringBuilder GetDeleteCommand(Function<cdr, String> whereClauseMethod) {
        return new StringBuilder("delete from cdr ").append(whereClauseMethod.apply(this));
    }
}
