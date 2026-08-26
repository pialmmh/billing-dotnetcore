package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the registered service-group detectors in {@code Id} order and returns the first that claims the
 * call — the lean equivalent of legacy {@code CdrProcessor.ExecuteServiceGroups} (unset ServiceGroup,
 * run each enabled SG, the first that sets {@code ServiceGroup &gt; 0} wins).
 *
 * <p>Per-tenant SG enablement (the legacy filtered by which idService has a ServiceGroupConfiguration;
 * for us, by which idService appears in the tenant's rate-plan-assignment tuples) is a future filter —
 * SG10 and SG11 are mutually exclusive by partnerType, so registering both is already safe.
 */
public final class ServiceGroupDetection {
    private final List<IServiceGroupDetector> _detectors;

    public ServiceGroupDetection(List<IServiceGroupDetector> detectors) {
        this._detectors = detectors.stream()
                .sorted(Comparator.comparingInt(IServiceGroupDetector::DetectionPriority))
                .collect(Collectors.toList());
    }

    /**
     * The default detection set: SG15 international-outgoing (evaluated FIRST via DetectionPriority so a {@code 00…}
     * call cannot fall into SG10), then SG10 outgoing and SG11 incoming. SG15 only claims {@code 00…} destinations,
     * so registering it universally does not disturb SG10/SG11 for domestic traffic.
     */
    public static ServiceGroupDetection Default() {
        return new ServiceGroupDetection(List.of(new SgIntlOutIptsp(), new SgDomOffnetOut(), new SgDomOffnetIn()));
    }

    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        cdr.ServiceGroup = 0;   // unset first, as the legacy loop did before each Execute
        for (var detector : _detectors) {
            var match = detector.Detect(cdr, partners);
            if (match != null) return match;
        }
        return null;
    }
}
