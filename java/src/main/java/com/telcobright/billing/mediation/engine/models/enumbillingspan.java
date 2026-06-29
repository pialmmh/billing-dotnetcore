// Ported VERBATIM from legacy Models_Mediation/enumbillingspan.cs (MediationModel.enumbillingspan).
// SCALAR fields kept verbatim; the EF navigation collection (rateplans) and the collection-initialising
// ctor REMOVED — not used by the rating logic. The rater uses ofbiz_uom_Id + `value` (= seconds).
package com.telcobright.billing.mediation.engine.models;

public class enumbillingspan {
    public String ofbiz_uom_Id;
    public String Type;
    public long value;
}
