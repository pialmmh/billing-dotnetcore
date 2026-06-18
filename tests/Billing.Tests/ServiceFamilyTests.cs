using Billing.Mediation.Model;
using Billing.Mediation.ServiceFamilies;
using MediationModel;

namespace Billing.Tests;

/// <summary>The two customer families' charge math (the legacy SfA2ZWithVatTax / SfDomOffNetInAns):
/// SF10 = A2Z amount + VAT(OtherAmount3); SF11 = ANS rate (rate−IOF) + BTRC.</summary>
public class ServiceFamilyTests
{
    [Fact]
    public void Sf10_charges_a2z_amount_plus_vat()
    {
        var rate = TestData.Ra(prefix: 1712, amount: 1.0m, otherAmount3: 0.5f);   // 50% VAT fraction
        var thisCdr = new cdr { DurationSec = 60m, InPartnerId = 5 };

        var ch = new SfA2ZWithVatTax().Charge(rate, thisCdr, serviceGroupId: 10, AssignmentDirection.Customer, 8);

        Assert.Equal(10, ch.servicegroup);
        Assert.Equal(10, ch.servicefamily);
        Assert.Equal(1.0m, ch.BilledAmount);        // 60s @ 1.0/min
        Assert.Equal(0.5m, ch.TaxAmount1);          // 1.0 * 0.5
        Assert.Equal("1712", ch.Prefix);
        Assert.Equal(0.5m, thisCdr.Tax1);
        Assert.Equal(1.0m, thisCdr.InPartnerCost);
        Assert.Equal(60m, thisCdr.Duration1);
    }

    [Fact]
    public void Sf1_supplier_leg_charges_out_partner_cost_with_tax_on_in_partner_cost()
    {
        // supplier rate 2.0/min; OtherAmount3 = 50 (a PERCENT for the base SfA2Z → /100).
        var rate = TestData.Ra(prefix: 1712, amount: 2.0m, otherAmount3: 50f);
        // the customer leg already set InPartnerCost = 1.0 (SfA2Z taxes on InPartnerCost even for supplier).
        var thisCdr = new cdr { DurationSec = 60m, OutPartnerId = 7, InPartnerCost = 1.0m };

        var ch = new SfA2Z().Charge(rate, thisCdr, serviceGroupId: 10, AssignmentDirection.Supplier, 8);

        Assert.Equal(1, ch.servicefamily);
        Assert.Equal(2.0m, ch.BilledAmount);        // 60s @ 2.0/min supplier rate
        Assert.Equal(2.0m, thisCdr.OutPartnerCost);
        Assert.Equal(2.0m, thisCdr.SupplierRate);
        Assert.Equal(0.5m, ch.TaxAmount1);          // InPartnerCost(1.0) * 50/100
        Assert.Equal(0.5m, thisCdr.Tax2);
    }

    [Fact]
    public void Sf11_charges_ans_rate_minus_iof_plus_btrc()
    {
        // rate 1.0, IOF 0.25 → effective 0.75; ANS = 60*0.75/60 = 0.75; BTRC = 0.75 * 0.5 = 0.375
        var rate = TestData.Ra(prefix: 1712, amount: 1.0m, otherAmount1: 0.25f, otherAmount3: 0.5f);
        var thisCdr = new cdr { DurationSec = 60m, OutPartnerId = 7 };

        var ch = new SfDomOffNetInAns().Charge(rate, thisCdr, serviceGroupId: 11, AssignmentDirection.Customer, 8);

        Assert.Equal(11, ch.servicefamily);
        Assert.Equal(0.75m, ch.BilledAmount);       // ANS amount
        Assert.Equal(0.375m, ch.TaxAmount1);        // BTRC
        Assert.Equal(0.375m, thisCdr.Tax1);
        Assert.Equal(0.75m, thisCdr.RevenueIgwOut);
        Assert.Equal(60m, thisCdr.RoundedDuration);
        Assert.Equal(1.0m, thisCdr.CustomerRate);
    }
}
