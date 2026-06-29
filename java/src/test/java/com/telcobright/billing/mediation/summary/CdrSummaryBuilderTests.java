// Faithful port of tests/Billing.Tests/CdrSummaryBuilderTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (CdrSummaryBuilder) per RULE T0.
package com.telcobright.billing.mediation.summary;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_02;
import com.telcobright.billing.mediation.engine.models.sum_voice_day_03;
import com.telcobright.billing.mediation.engine.models.sum_voice_hr_03;

/**
 * Building the per-call sum_voice_* summary from the cdr + customer chargeable: the right table per SG, the
 * common identity/count/duration fields, the day bucket, the customer cost/tax, and that two like calls
 * share a tuple key and merge.
 */
class CdrSummaryBuilderTests {

    private static cdr Sg10Cdr() {
        cdr c = new cdr();
        c.SwitchId = 1; c.InPartnerId = 5; c.OutPartnerId = 0;
        c.IncomingRoute = "in"; c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1"; c.TerminatingIP = "2.2.2.2";
        c.StartTime = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        c.ConnectTime = LocalDateTime.of(2026, 6, 19, 14, 30, 1);
        c.ChargingStatus = 1; c.DurationSec = BigDecimal.valueOf(60);
        c.RoundedDuration = BigDecimal.valueOf(60); c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880"; c.AnsIdTerm = 42; c.MatchedPrefixSupplier = "1712";
        return c;
    }

    private static acc_chargeable Sg10Chargeable() {
        acc_chargeable c = new acc_chargeable();
        c.servicegroup = 10; c.servicefamily = 10;
        c.BilledAmount = new BigDecimal("1.0"); c.TaxAmount1 = new BigDecimal("0.5");
        c.Prefix = "1712"; c.unitPriceOrCharge = new BigDecimal("1.0"); c.idBilledUom = "BDT";
        return c;
    }

    @Test
    void Sg10_builds_day_03_with_customer_cost_and_tax() {
        var summary = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);

        assertEquals(sum_voice_day_03.class, summary.getClass());
        assertEquals(LocalDateTime.of(2026, 6, 19, 0, 0), summary.tup_starttime);   // day bucket
        assertEquals(1L, summary.totalcalls);
        assertEquals(1L, summary.successfulcalls);
        assertEquals(0, new BigDecimal("60").compareTo(summary.actualduration));
        assertEquals(0, new BigDecimal("1.0").compareTo(summary.customercost));
        assertEquals(0, new BigDecimal("0.5").compareTo(summary.tax1));
        assertEquals("1712", summary.tup_matchedprefixcustomer);
        assertEquals("880", summary.tup_countryorareacode);
        assertEquals("42", summary.tup_destinationId);
    }

    @Test
    void Sg10_picks_up_supplier_leg_fields() {
        var cdr = Sg10Cdr();
        cdr.OutPartnerCost = new BigDecimal("2.0");   // set by SfA2Z's supplier leg
        cdr.SupplierRate = new BigDecimal("2.0");
        cdr.Tax2 = new BigDecimal("0.3");

        var summary = CdrSummaryBuilder.Build(cdr, Sg10Chargeable(), SummaryBucket.Day);

        assertEquals(0, new BigDecimal("2.0").compareTo(summary.suppliercost));
        assertEquals(0, new BigDecimal("2.0").compareTo(summary.tup_supplierrate));
        assertEquals(0, new BigDecimal("0.3").compareTo(summary.tax2));
    }

    @Test
    void Sg11_builds_day_02() {
        var cdr = Sg10Cdr();
        cdr.MatchedPrefixY = "1712";
        cdr.AnsIdOrig = 7;
        var chargeable = Sg10Chargeable();
        chargeable.servicegroup = 11;
        chargeable.servicefamily = 11;

        var summary = CdrSummaryBuilder.Build(cdr, chargeable, SummaryBucket.Day);

        assertEquals(sum_voice_day_02.class, summary.getClass());
        assertEquals(0, new BigDecimal("1.0").compareTo(summary.customercost));
        assertEquals("7", summary.tup_sourceId);
        assertEquals("1712", summary.tup_matchedprefixcustomer);
    }

    @Test
    void Hour_bucket_rounds_down_to_the_hour() {
        var summary = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Hour);
        assertEquals(sum_voice_hr_03.class, summary.getClass());
        assertEquals(LocalDateTime.of(2026, 6, 19, 14, 0, 0), summary.tup_starttime);
    }

    @Test
    void Like_calls_share_a_tuple_key_and_merge() {
        var a = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);
        var b = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);

        assertEquals(a.GetTupleKey(), b.GetTupleKey());   // same identity -> same rollup bucket
        a.Merge(b);
        assertEquals(2L, a.totalcalls);
        assertEquals(0, new BigDecimal("2.0").compareTo(a.customercost));
        assertEquals(0, new BigDecimal("1.0").compareTo(a.tax1));
    }
}
