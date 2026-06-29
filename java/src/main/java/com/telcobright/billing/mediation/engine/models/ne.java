// Ported from legacy Models_Mediation/ne.cs (MediationModel.ne — a switch / network element).
// SCALAR fields kept verbatim; EF navigation properties (bridgedroutes/jobs/routes/enumcdrformat/
// telcobrightpartner) and the collection-initialising ctor REMOVED (EF tech, not used by mediation).
// Most fields are legacy file-decode config and become vestigial under the routesphere feed.
package com.telcobright.billing.mediation.engine.models;

import java.time.LocalDateTime;

public class ne {
    public int idSwitch;
    public int idCustomer;
    public int idcdrformat;
    public int idMediationRule;
    public String SwitchName;
    public String CDRPrefix;
    public String FileExtension;
    public String Description;
    public String SourceFileLocations;
    public String BackupFileLocations;
    public Integer LoadingStopFlag;
    public Integer LoadingSpanCount;
    public Integer TransactionSizeForCDRLoading;
    public Integer DecodingSpanCount;
    public Integer SkipAutoCreateJob;
    public Integer SkipCdrListed;
    public Integer SkipCdrReceived;
    public Integer SkipCdrDecoded;
    public Integer SkipCdrBackedup;
    public Integer KeepDecodedCDR;
    public Integer KeepReceivedCdrServer;
    public Integer CcrCauseCodeField;
    public Integer SwitchTimeZoneId;
    public String CallConnectIndicator;
    public int FieldNoForTimeSummary;
    public String EnableSummaryGeneration;
    public int ExistingSummaryCacheSpanHr;
    public int BatchToDecodeRatio;
    public int FilterDuplicateCdr;
    public int UseIdCallAsBillId;
    public int PrependLocationNumberToFileName;
    public int AllowEmptyFile;
    public String ipOrTdm;
}
