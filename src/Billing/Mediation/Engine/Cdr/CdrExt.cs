// Ported from legacy Mediation/Cdr/CdrExt.cs (TelcobrightMediation.Cdr).
// TRIMMED for the routesphere feed: kept Cdr + Chargeables (the (sg,sf,dir) chargeable map) +
// TableWiseSummaries + MediationResult. STRIPPED: PartialCdrContainer (partial-CDR/decode merge — you
// said ignore decode/pre-processing) and the AccWiseTransactionContainer maps (accounting/ledger —
// mem-ledger stays in routesphere). cdrerror path simplified away.
#nullable disable
using System;
using System.Collections.Generic;
using MediationModel;
using MediationModel.enums;

namespace TelcobrightMediation.Cdr
{
    public enum CdrNewOldType
    {
        NewCdr,
        OldCdr
    }

    public class CdrExt
    {
        public CdrNewOldType CdrNewOldType { get; }
        public string UniqueBillId => this.Cdr.UniqueBillId;
        public long IdCall => this.Cdr.IdCall;
        public DateTime StartTime => this.Cdr.StartTime;
        public cdr Cdr { get; set; }

        public Dictionary<CdrSummaryType, AbstractCdrSummary> TableWiseSummaries { get; set; }

        public Dictionary<ValueTuple<int, int, int>, acc_chargeable> Chargeables { get; }
            = new Dictionary<ValueTuple<int, int, int>, acc_chargeable>(); // key = (sg, sf, assignedDir)

        public CdrMediationResult MediationResult { get; set; }

        public CdrExt(cdr cdr, CdrNewOldType cdrExtType)
        {
            this.Cdr = cdr;
            this.CdrNewOldType = cdrExtType;
            this.TableWiseSummaries = new Dictionary<CdrSummaryType, AbstractCdrSummary>();
        }

        public override string ToString() => this.UniqueBillId + "/" + this.Cdr.IdCall;
    }
}
