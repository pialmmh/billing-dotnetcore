// Ported VERBATIM from legacy Mediation/Cdr/CdrMediationResult.cs (TelcobrightMediation).
package com.telcobright.billing.mediation.engine.models;

public class CdrMediationResult {
    public cdr Cdr;
    public CdrMediationResult(cdr aggregatedCdr) {
        this.Cdr = aggregatedCdr;
    }

    /** C# computed property {@code MediationComplete => Convert.ToBoolean(this.Cdr.MediationComplete)}. */
    public boolean MediationComplete() {
        return this.Cdr.MediationComplete != null && this.Cdr.MediationComplete != 0;
    }

    /** C# computed property {@code ChargingStatus => Convert.ToInt32(this.Cdr.ChargingStatus)}. */
    public int ChargingStatus() {
        return this.Cdr.ChargingStatus != null ? this.Cdr.ChargingStatus : 0;
    }

    /** C# computed property {@code Connected => this.Cdr.ConnectTime != null}. */
    public boolean Connected() {
        return this.Cdr.ConnectTime != null;
    }

    /** C# computed property {@code ConnectedByCauseCode => Convert.ToBoolean(this.Cdr.NERSuccess)}. */
    public boolean ConnectedByCauseCode() {
        return this.Cdr.NERSuccess != null && this.Cdr.NERSuccess != 0;
    }
}
