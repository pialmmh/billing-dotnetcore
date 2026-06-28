// Ported from legacy Models_Mediation/ne.cs (MediationModel.ne — a switch / network element).
// SCALAR fields kept verbatim; EF navigation properties (bridgedroutes/jobs/routes/enumcdrformat/
// telcobrightpartner) and the collection-initialising ctor REMOVED (EF tech, not used by mediation).
// Most fields are legacy file-decode config and become vestigial under the routesphere feed.
#nullable disable

namespace MediationModel
{
    using System;
    using System.Collections.Generic;

    public partial class ne
    {
        public int idSwitch { get; set; }
        public int idCustomer { get; set; }
        public int idcdrformat { get; set; }
        public int idMediationRule { get; set; }
        public string SwitchName { get; set; }
        public string CDRPrefix { get; set; }
        public string FileExtension { get; set; }
        public string Description { get; set; }
        public string SourceFileLocations { get; set; }
        public string BackupFileLocations { get; set; }
        public Nullable<int> LoadingStopFlag { get; set; }
        public Nullable<int> LoadingSpanCount { get; set; }
        public Nullable<int> TransactionSizeForCDRLoading { get; set; }
        public Nullable<int> DecodingSpanCount { get; set; }
        public Nullable<int> SkipAutoCreateJob { get; set; }
        public Nullable<int> SkipCdrListed { get; set; }
        public Nullable<int> SkipCdrReceived { get; set; }
        public Nullable<int> SkipCdrDecoded { get; set; }
        public Nullable<int> SkipCdrBackedup { get; set; }
        public Nullable<int> KeepDecodedCDR { get; set; }
        public Nullable<int> KeepReceivedCdrServer { get; set; }
        public Nullable<int> CcrCauseCodeField { get; set; }
        public Nullable<int> SwitchTimeZoneId { get; set; }
        public string CallConnectIndicator { get; set; }
        public int FieldNoForTimeSummary { get; set; }
        public string EnableSummaryGeneration { get; set; }
        public int ExistingSummaryCacheSpanHr { get; set; }
        public int BatchToDecodeRatio { get; set; }
        public int FilterDuplicateCdr { get; set; }
        public int UseIdCallAsBillId { get; set; }
        public int PrependLocationNumberToFileName { get; set; }
        public int AllowEmptyFile { get; set; }
        public string ipOrTdm { get; set; }
    }
}
