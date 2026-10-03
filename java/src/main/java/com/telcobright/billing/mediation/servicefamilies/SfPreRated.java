package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.math.BigDecimal;

/**
 * SF 30 — the PRE-RATED family: the service family of SG 30, an ad view (routesphere
 * {@code docs/architecture/ad-is-a-call.md} §5). The switch's settle step has ALREADY charged the ledger, so this
 * family looks no rate up, runs no rater and charges nothing again: it writes down what was charged. It builds ONE
 * chargeable from the record's own settled facts, and it leaves the cdr's amounts exactly as the wire sent them.
 *
 * <p>The chargeable, field by field:
 * <ul>
 *   <li>{@code BilledAmount} — {@code PackageAmount} when the tier paid in package UNITS (it is &gt; 0), else
 *       {@code InPartnerCost}, the MONEY (0 for a view nobody was admitted for);</li>
 *   <li>{@code idBilledUom} — the unit as the wire sent it ({@code InPartnerUom}). The summary keeps money and units
 *       as two measures and tells them apart by this field, so a record that names no unit and paid nothing in
 *       units — a refused view — is {@link #MoneyUom}: it sums as money 0 and never opens a units row;</li>
 *   <li>{@code Quantity} / {@code idQuantityUom} — the seconds watched ({@code DurationSec}), in {@code TF_s}, as a
 *       call's billed seconds are;</li>
 *   <li>{@code unitPriceOrCharge} — the rate the switch charged at ({@code CustomerRate}); 0 when the wire sent
 *       none;</li>
 *   <li>{@code Prefix} — the matched rate prefix ({@code MatchedPrefixCustomer});</li>
 *   <li>{@code uniqueBillId} / {@code idEvent} / {@code transactionTime} — the cdr's {@code UniqueBillId},
 *       {@code IdCall}, {@code StartTime}, as every family.</li>
 * </ul>
 *
 * <p><b>{@code glAccountId} stays 0</b> (architect's ruling 2026-10-04). It is a general-ledger account everywhere
 * else, and a package account written there would be posted as one by any later GL step. The ledger account of an
 * ad view is on its cdr row ({@code IdPackageAccount}); a chargeable reaches its cdr by {@code uniqueBillId} /
 * {@code idEvent}. {@code RateId} and {@code ProductId} stay 0 and the taxes null: no rate row was matched.
 *
 * <p>On the cdr it stamps only the two durations a rater would have: {@code Duration1} and {@code RoundedDuration}
 * = {@code DurationSec} — no plan rounds a view, so the billed and the rounded duration ARE the watched one.
 */
public final class SfPreRated implements IServiceFamily {
    public static final int FamilyId = 30;

    /** The unit of an amount charged in money. The wire's own money fields are in it ({@code callRatePerMinBDT}). */
    public static final String MoneyUom = "BDT";
    /** The unit of the quantity: seconds, as on every call chargeable. */
    public static final String SecondsUom = "TF_s";

    @Override public int Id() { return FamilyId; }

    /** {@code rate} is ignored — there is none: the record is pre-rated. */
    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        StampTheDurationsNoPlanRounds(cdr);
        return ChargeableOfWhatWasSettled(cdr, serviceGroupId, direction);
    }

    private static void StampTheDurationsNoPlanRounds(cdr cdr) {
        if (cdr.DurationSec == null) return;
        cdr.Duration1 = cdr.DurationSec;
        cdr.RoundedDuration = cdr.DurationSec;
    }

    private acc_chargeable ChargeableOfWhatWasSettled(cdr cdr, int serviceGroupId, AssignmentDirection direction) {
        boolean paidInUnits = cdr.PackageAmount != null && cdr.PackageAmount.signum() > 0;
        var c = new acc_chargeable();
        c.servicegroup = serviceGroupId;
        c.servicefamily = Id();
        c.assignedDirection = (byte) direction.value;
        c.BilledAmount = paidInUnits ? cdr.PackageAmount : OrZero(cdr.InPartnerCost);
        c.idBilledUom = BilledUomOf(cdr, paidInUnits);
        c.Quantity = cdr.DurationSec;
        c.idQuantityUom = SecondsUom;
        c.unitPriceOrCharge = OrZero(cdr.CustomerRate);
        c.Prefix = cdr.MatchedPrefixCustomer;
        c.description = "nc";                       // a new cdr, as ChargeableBuilder tags it
        c.uniqueBillId = cdr.UniqueBillId;
        c.idEvent = cdr.IdCall;
        c.transactionTime = cdr.StartTime;
        return c;
    }

    /** The unit as sent. A record that names none: money, unless it paid in units — then the unit is not known. */
    private static String BilledUomOf(cdr cdr, boolean paidInUnits) {
        if (cdr.InPartnerUom != null && !cdr.InPartnerUom.isBlank()) return cdr.InPartnerUom;
        return paidInUnits ? null : MoneyUom;
    }

    private static BigDecimal OrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
