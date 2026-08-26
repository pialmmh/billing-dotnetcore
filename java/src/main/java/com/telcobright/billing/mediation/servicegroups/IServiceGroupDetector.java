package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;

/**
 * The lean port of a legacy {@code IServiceGroup}'s DETECTION role. It inspects a cdr against the
 * tenant's partners and, if it claims the call, sets {@code cdr.ServiceGroup} to its {@code Id},
 * normalizes the chargeable number, and returns the {@link ServiceGroupMatch}; otherwise it
 * returns {@code null} and leaves the cdr untouched. One detector per service group (SG10 outgoing,
 * SG11 incoming).
 *
 * <p>The legacy {@code Execute} also ran {@code AnsPrefixFinder} to stamp country/destination ids onto the
 * cdr — that feeds the summary tuple, not the basic charge, so it is deferred to the summary slice.
 */
public interface IServiceGroupDetector {
    int Id();
    String RuleName();
    ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners);

    /**
     * Evaluation order within {@link ServiceGroupDetection} (lower runs first). Defaults to {@link #Id()} so the
     * legacy "first enabled SG that claims the call wins" is preserved by ascending id. A detector overrides this
     * only when it must be checked ahead of a lower-id one — e.g. SG15 international-outgoing must precede SG10,
     * which would otherwise capture the same call on partner type alone.
     */
    default int DetectionPriority() { return Id(); }
}
