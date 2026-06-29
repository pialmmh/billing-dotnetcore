package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two customer families' charge math (the legacy SfA2ZWithVatTax / SfDomOffNetInAns):
 * SF10 = A2Z amount + VAT(OtherAmount3); SF11 = ANS rate (rate-IOF) + BTRC. Faithful port of
 * ServiceFamilyTests.cs. BigDecimal assertions compare by value ({@code compareTo == 0}, RULE T2).
 */
class ServiceFamilyTests {

    @Test
    void Sf10_charges_a2z_amount_plus_vat() {
        rateassign rate = TestData.Ra(1712, "1.0").otherAmount3(0.5f).build();   // 50% VAT fraction
        cdr thisCdr = new cdr();
        thisCdr.DurationSec = BigDecimal.valueOf(60);
        thisCdr.InPartnerId = 5;

        acc_chargeable ch = new SfA2ZWithVatTax().Charge(rate, thisCdr, 10, AssignmentDirection.Customer, 8);

        assertEquals(10, ch.servicegroup);
        assertEquals(10, ch.servicefamily);
        assertEquals(0, new BigDecimal("1.0").compareTo(ch.BilledAmount));        // 60s @ 1.0/min
        assertEquals(0, new BigDecimal("0.5").compareTo(ch.TaxAmount1));          // 1.0 * 0.5
        assertEquals("1712", ch.Prefix);
        assertEquals(0, new BigDecimal("0.5").compareTo(thisCdr.Tax1));
        assertEquals(0, new BigDecimal("1.0").compareTo(thisCdr.InPartnerCost));
        assertEquals(0, new BigDecimal("60").compareTo(thisCdr.Duration1));
    }

    @Test
    void Sf1_supplier_leg_charges_out_partner_cost_with_tax_on_in_partner_cost() {
        // supplier rate 2.0/min; OtherAmount3 = 50 (a PERCENT for the base SfA2Z -> /100).
        rateassign rate = TestData.Ra(1712, "2.0").otherAmount3(50f).build();
        // the customer leg already set InPartnerCost = 1.0 (SfA2Z taxes on InPartnerCost even for supplier).
        cdr thisCdr = new cdr();
        thisCdr.DurationSec = BigDecimal.valueOf(60);
        thisCdr.OutPartnerId = 7;
        thisCdr.InPartnerCost = new BigDecimal("1.0");

        acc_chargeable ch = new SfA2Z().Charge(rate, thisCdr, 10, AssignmentDirection.Supplier, 8);

        assertEquals(1, ch.servicefamily);
        assertEquals(0, new BigDecimal("2.0").compareTo(ch.BilledAmount));        // 60s @ 2.0/min supplier rate
        assertEquals(0, new BigDecimal("2.0").compareTo(thisCdr.OutPartnerCost));
        assertEquals(0, new BigDecimal("2.0").compareTo(thisCdr.SupplierRate));
        assertEquals(0, new BigDecimal("0.5").compareTo(ch.TaxAmount1));          // InPartnerCost(1.0) * 50/100
        assertEquals(0, new BigDecimal("0.5").compareTo(thisCdr.Tax2));
    }

    @Test
    void Sf11_charges_ans_rate_minus_iof_plus_btrc() {
        // rate 1.0, IOF 0.25 -> effective 0.75; ANS = 60*0.75/60 = 0.75; BTRC = 0.75 * 0.5 = 0.375
        rateassign rate = TestData.Ra(1712, "1.0").otherAmount1(0.25f).otherAmount3(0.5f).build();
        cdr thisCdr = new cdr();
        thisCdr.DurationSec = BigDecimal.valueOf(60);
        thisCdr.OutPartnerId = 7;

        acc_chargeable ch = new SfDomOffNetInAns().Charge(rate, thisCdr, 11, AssignmentDirection.Customer, 8);

        assertEquals(11, ch.servicefamily);
        assertEquals(0, new BigDecimal("0.75").compareTo(ch.BilledAmount));       // ANS amount
        assertEquals(0, new BigDecimal("0.375").compareTo(ch.TaxAmount1));        // BTRC
        assertEquals(0, new BigDecimal("0.375").compareTo(thisCdr.Tax1));
        assertEquals(0, new BigDecimal("0.75").compareTo(thisCdr.RevenueIgwOut));
        assertEquals(0, new BigDecimal("60").compareTo(thisCdr.RoundedDuration));
        assertEquals(0, new BigDecimal("1.0").compareTo(thisCdr.CustomerRate));
    }
}
