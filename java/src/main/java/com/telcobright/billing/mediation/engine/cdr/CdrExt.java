// Ported from legacy Mediation/Cdr/CdrExt.cs (TelcobrightMediation.Cdr).
// TRIMMED for the routesphere feed: kept Cdr + Chargeables (the (sg,sf,dir) chargeable map) +
// TableWiseSummaries + MediationResult. STRIPPED: PartialCdrContainer and the AccWiseTransactionContainer
// maps. cdrerror path simplified away.
// NOTE: despite the file name, this declares NO C# extension methods — it is a plain wrapper class around
// a `cdr` with computed accessors (UniqueBillId/IdCall/StartTime ported to methods per RULE 4).
package com.telcobright.billing.mediation.engine.cdr;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrMediationResult;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CdrExt {
    public CdrNewOldType CdrNewOldType;
    public cdr Cdr;

    public Map<CdrSummaryType, AbstractCdrSummary> TableWiseSummaries;

    // key = (sg, sf, assignedDir); C# ValueTuple<int,int,int> -> java.util.List<Integer> (value equality).
    public Map<List<Integer>, acc_chargeable> Chargeables = new HashMap<>();

    public CdrMediationResult MediationResult;

    public CdrExt(cdr cdr, CdrNewOldType cdrExtType) {
        this.Cdr = cdr;
        this.CdrNewOldType = cdrExtType;
        this.TableWiseSummaries = new HashMap<>();
    }

    /** C# computed property {@code UniqueBillId => this.Cdr.UniqueBillId}. */
    public String UniqueBillId() { return this.Cdr.UniqueBillId; }

    /** C# computed property {@code IdCall => this.Cdr.IdCall}. */
    public long IdCall() { return this.Cdr.IdCall; }

    /** C# computed property {@code StartTime => this.Cdr.StartTime}. */
    public LocalDateTime StartTime() { return this.Cdr.StartTime; }

    @Override
    public String toString() { return this.UniqueBillId() + "/" + this.Cdr.IdCall; }
}
