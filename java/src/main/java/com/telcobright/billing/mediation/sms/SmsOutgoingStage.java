package com.telcobright.billing.mediation.sms;

import com.telcobright.billing.mediation.cdr.RatedCdr;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.validation.IValidationRule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * The SMS-only steps {@code CdrPipeline} runs when it was built by {@code CdrPipeline.SmsOutgoing}. The voice
 * pipeline never holds one of these, so none of this runs for voice.
 */
public final class SmsOutgoingStage {
    private final SmsBillingRuleCatalog _catalog;
    private final ISmsAccountingStore _store;
    private final Supplier<LocalDateTime> _clock;

    public SmsOutgoingStage(SmsBillingRuleCatalog catalog, ISmsAccountingStore store, Supplier<LocalDateTime> clock) {
        if (catalog == null || store == null) throw new IllegalArgumentException("SMS stage needs the catalog and the accounting store");
        _catalog = catalog;
        _store = store;
        _clock = clock != null ? clock : LocalDateTime::now;
    }

    /**
     * Legacy MediationValidator against the FIXED SG20 configuration: the tenant's common checklist, SG &gt; 0,
     * then SG20's answered/unanswered checklist ({@link SmsOutgoing#Configuration}).
     */
    public String Validate(cdr cdr, MediationContext mediation) {
        for (IValidationRule<cdr> rule : mediation.CommonChecklist)
            if (!rule.Validate(cdr)) return rule.ValidationMessage();
        if (cdr.ServiceGroup <= 0) return "ServiceGroup must be > 0";
        ServiceGroupConfiguration sg = SmsOutgoing.Configuration;
        List<IValidationRule<cdr>> checklist = ((cdr.ChargingStatus != null ? cdr.ChargingStatus : 0) == 1)
                ? sg.AnsweredChecklist() : sg.UnansweredChecklist();
        for (IValidationRule<cdr> rule : checklist)
            if (!rule.Validate(cdr)) return rule.ValidationMessage();
        return "";
    }

    /**
     * Per-cdr accounting pre-check, run while the cdr can still be routed to cdrerror: every customer chargeable
     * must resolve a billing rule and a posting account key (partner + rate-plan currency). Throws otherwise —
     * the pipeline catches it and the SMS lands in cdrerror instead of being billed without its ledger posting.
     */
    public void Qualify(cdr cdr, List<acc_chargeable> chargeables) {
        for (acc_chargeable c : chargeables) {
            if (!SmsAccountingEngine.IsCustomerLeg(c)) continue;
            var rule = _catalog.Rule(c.idBillingrule);
            if (rule == null)
                throw new IllegalStateException("Billing rule " + c.idBillingrule + " not found");
            SmsAccountingEngine.PostingAccountTemplate(cdr, c, rule);   // validates partner + currency
        }
    }

    /** Legacy mediation flags: rated rows are complete, errored rows are not. */
    public void MarkMediation(List<RatedCdr> rated, List<cdr> errored) {
        for (RatedCdr r : rated) r.Cdr().MediationComplete = 1;
        for (cdr c : errored) c.MediationComplete = 0;
    }

    /** Post the batch's accounting (accounts, transactions, balances, ledger, cdr totals) and write it. */
    public int PostAccounting(ISqlExecutor sql, List<RatedCdr> rated, IAutoIncrementManager ids, int segmentSize) {
        var plan = SmsAccountingEngine.Post(rated, _catalog, _store, ids, _clock.get());
        return SmsAccountingWriter.Write(sql, plan, segmentSize);
    }

    /**
     * Fit errored rows to the SMS-schema {@code cdrerror} column widths. {@code AdditionalMetaData} there is
     * VARCHAR(100) (TEXT in {@code cdr}); an over-long Base64 body is cut to its first 100 chars rather than
     * letting a STRICT-mode overflow roll back — and endlessly replay — the whole batch.
     */
    public void FitErrorRows(List<cdr> errored) {
        for (cdr c : errored)
            if (c.AdditionalMetaData != null && c.AdditionalMetaData.length() > SmsOutgoing.CdrErrorMetaDataMaxLen)
                c.AdditionalMetaData = c.AdditionalMetaData.substring(0, SmsOutgoing.CdrErrorMetaDataMaxLen);
    }
}
