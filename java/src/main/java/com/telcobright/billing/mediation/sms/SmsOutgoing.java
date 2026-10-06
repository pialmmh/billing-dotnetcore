package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.validation.DurationSecGtEq0;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.mediation.validation.InPartnerIdGt0;
import com.telcobright.billing.mediation.validation.OutPartnerIdGt0;
import com.telcobright.billing.mediation.validation.ServiceGroupGt0;

import java.util.List;

/**
 * The fixed facts of the OUTGOING SMS path (legacy {@code SgDomSmsOffnetOut}, SG 20). Everything that
 * enters through the SMS intake IS SG20 — the service group is a property of the pipeline, never detected
 * from the partner type and never read from the event.
 *
 * <p>The rating rules and checklists are the legacy SG20 configuration: SF10 customer leg only
 * (the legacy SMS biller produced no cost leg — every billed SMS carries a single sf10/dir1 chargeable), qualified by
 * {@code DurationSecGtEq0, InPartnerIdGt0, OutPartnerIdGt0, ServiceGroupGt0}. They are pinned HERE rather than
 * read from {@code MediationContext.ServiceGroupConfigurations}, so the voice defaults are untouched and a
 * config-manager payload without an SG20 entry cannot silently disable SMS rating.</p>
 */
public final class SmsOutgoing {
    private SmsOutgoing() {}

    /** Legacy {@code SgDomSmsOffnetOut.Id}. */
    public static final int ServiceGroupId = 20;
    public static final String ServiceGroupName = "Domestic Outgoing SMS";
    /** The customer family (legacy {@code SfA2ZWithVatTax}, {@code ServiceFamilyType.A2ZRatingWithVatTax}). */
    public static final int CustomerServiceFamilyId = 10;

    /** Provenance marker on every cdr/cdrerror row this path writes — distinct from voice's {@code kafka:cdr}. */
    public static final String FileNameMarker = "kafka:sms";

    /** Legacy account depth and product for the SG20 posting account ({@code d0/…/pd0/…}). */
    public static final int AccountDepth = 0;
    public static final int AccountProduct = 0;

    /**
     * Persisted error text cap for this path: {@code cdrerror.ErrorCode} is {@code VARCHAR(100)} on the SMS
     * schema (verified 2026-10-06; voice schemas are 500). The servers run STRICT_TRANS_TABLES, so an overflow
     * would roll back the whole batch and the Kafka rewind would replay it forever.
     */
    public static final int ErrorCodeMaxLen = 100;
    /**
     * {@code cdrerror.AdditionalMetaData} is {@code VARCHAR(100)} on the SMS schema while {@code cdr.AdditionalMetaData}
     * is TEXT. A Base64 SMS body routinely exceeds 100 chars, so an errored SMS keeps a 100-char prefix of the
     * message there (the full, verbatim body lands in {@code cdr} when the SMS rates).
     */
    public static final int CdrErrorMetaDataMaxLen = 100;
    /** {@code acc_chargeable.Prefix} is {@code VARCHAR(30)}. */
    public static final int ChargeablePrefixMaxLen = 30;

    /** SG20's fixed configuration: SF10 customer leg + the legacy SG20 answered/unanswered checklists. */
    public static final ServiceGroupConfiguration Configuration = new ServiceGroupConfiguration(
            ServiceGroupId, false,
            List.<Rule>of(new RatingRule(CustomerServiceFamilyId, AssignmentDirection.Customer.value, null)),
            Checklist(), Checklist());

    private static List<IValidationRule<cdr>> Checklist() {
        return List.of(new DurationSecGtEq0(), new InPartnerIdGt0(), new OutPartnerIdGt0(), new ServiceGroupGt0());
    }
}
