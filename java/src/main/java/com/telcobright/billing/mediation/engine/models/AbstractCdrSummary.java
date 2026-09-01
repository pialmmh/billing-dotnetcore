// Ported from legacy Models_Mediation/EntityExtensions/_Summary/AbstractCdrSummary.cs.
// KEPT: the summary data shape + aggregation business logic (GetTupleKey / Merge / Multiply /
// CloneWithFakeId). STRIPPED: the raw-MySQL writer methods (GetExtInsertValues / GetUpdateCommand /
// GetDeleteCommand) and the ICacheble marker — those are DB-write tech, re-added on the data layer
// via the single shared MySqlConnection. tup_starttime.ToMySqlField() -> ToString(...) to drop the
// LibraryExtensions dependency.
package com.telcobright.billing.mediation.engine.models;

import com.telcobright.billing.mediation.sql.ICacheble;
import com.telcobright.billing.mediation.sql.MySqlFieldExtensions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * FAITHFUL-PORT NOTE: the C# generic key type {@code CdrSummaryTuple} (a 20-element nested
 * {@code ValueTuple<...>}) has no Java analogue; it is represented as a {@code java.util.List<Object>}
 * built (flattened, in the same field order) by {@link #GetTupleKey()}. {@code java.util.Arrays.asList}
 * gives the same structural value-equality / hashCode a {@code ValueTuple} key needs. (BigDecimal
 * elements compare scale-sensitively in a List key vs C# decimal value-equality — see report.)
 *
 * <p>FAITHFUL-PORT NOTE: the C# {@code abstract} auto-properties (each {@code override}n in the concrete
 * {@code sum_voice_*} subclasses) become plain public fields declared once here; Java inherits fields, so
 * the subclasses are empty. Behaviour ({@code summary.id}, {@code summary.totalcalls}, ...) is identical.</p>
 */
public abstract class AbstractCdrSummary
        implements ISummary<AbstractCdrSummary, List<Object>>, ICacheble<AbstractCdrSummary> {

    protected AbstractCdrSummary() { } // don't remove, required at runtime by some code

    public long id;
    public int tup_switchid;
    public int tup_inpartnerid;
    public int tup_outpartnerid;
    public String tup_incomingroute;
    public String tup_outgoingroute;
    public BigDecimal tup_customerrate = BigDecimal.ZERO;
    public BigDecimal tup_supplierrate = BigDecimal.ZERO;
    public String tup_incomingip;
    public String tup_outgoingip;
    public String tup_countryorareacode;
    public String tup_matchedprefixcustomer;
    public String tup_matchedprefixsupplier;
    public String tup_sourceId;
    public String tup_destinationId;
    public String tup_customercurrency;
    public String tup_suppliercurrency;
    public String tup_tax1currency;
    public String tup_tax2currency;
    public String tup_vatcurrency;
    public LocalDateTime tup_starttime;
    public long totalcalls;
    public long connectedcalls;
    public long connectedcallsCC;
    public long successfulcalls;
    public BigDecimal actualduration = BigDecimal.ZERO;
    public BigDecimal roundedduration = BigDecimal.ZERO;
    public BigDecimal duration1 = BigDecimal.ZERO;
    public BigDecimal duration2 = BigDecimal.ZERO;
    public BigDecimal duration3 = BigDecimal.ZERO;
    public BigDecimal PDD = BigDecimal.ZERO;
    public BigDecimal customercost = BigDecimal.ZERO;
    public BigDecimal suppliercost = BigDecimal.ZERO;
    public BigDecimal tax1 = BigDecimal.ZERO;
    public BigDecimal tax2 = BigDecimal.ZERO;
    public BigDecimal vat = BigDecimal.ZERO;
    public int intAmount1;
    public int intAmount2;
    public int intAmount3;
    public long longAmount1;
    public long longAmount2;
    public long longAmount3;
    public BigDecimal longDecimalAmount1 = BigDecimal.ZERO;
    public BigDecimal longDecimalAmount2 = BigDecimal.ZERO;
    public BigDecimal longDecimalAmount3 = BigDecimal.ZERO;
    public BigDecimal decimalAmount1 = BigDecimal.ZERO;
    public BigDecimal decimalAmount2 = BigDecimal.ZERO;
    public BigDecimal decimalAmount3 = BigDecimal.ZERO;

    // ISummary.id { get; set; } exposed as accessors so generic-bound code (SummaryCache) can reach it;
    // the public `id` field above is the single backing store (RULE 4 field access stays valid).
    @Override
    public long getId() { return this.id; }

    @Override
    public void setId(long value) { this.id = value; }

    @Override
    public List<Object> GetTupleKey() {
        // C# builds nested ValueTuples (tup3 inside tup2 inside tup1); flattened here in the same order.
        return Arrays.asList(
                this.tup_switchid,
                this.tup_inpartnerid,
                this.tup_outpartnerid,
                this.tup_incomingroute,
                this.tup_outgoingroute,
                this.tup_customerrate.stripTrailingZeros(),   // C# decimal equality is scale-insensitive
                this.tup_supplierrate.stripTrailingZeros(),
                this.tup_incomingip,
                this.tup_outgoingip,
                this.tup_countryorareacode,
                this.tup_matchedprefixcustomer,
                this.tup_matchedprefixsupplier,
                this.tup_sourceId,
                this.tup_destinationId,
                this.tup_tax1currency,
                this.tup_tax2currency,
                this.tup_vatcurrency,
                this.tup_starttime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                this.tup_customercurrency,
                this.tup_suppliercurrency
        );
    }

    @Override
    public void Merge(AbstractCdrSummary newSummary) {
        this.totalcalls += newSummary.totalcalls;
        this.connectedcalls += newSummary.connectedcalls;
        this.connectedcallsCC += newSummary.connectedcallsCC;
        this.successfulcalls += newSummary.successfulcalls;
        this.actualduration = this.actualduration.add(newSummary.actualduration);
        this.roundedduration = this.roundedduration.add(newSummary.roundedduration);
        this.duration1 = this.duration1.add(newSummary.duration1);
        this.duration2 = this.duration2.add(newSummary.duration2);
        this.duration3 = this.duration3.add(newSummary.duration3);
        this.PDD = this.PDD.add(newSummary.PDD);
        this.customercost = this.customercost.add(newSummary.customercost);
        this.suppliercost = this.suppliercost.add(newSummary.suppliercost);
        this.tax1 = this.tax1.add(newSummary.tax1);
        this.tax2 = this.tax2.add(newSummary.tax2);
        this.vat = this.vat.add(newSummary.vat);
        this.intAmount1 += newSummary.intAmount1;
        this.intAmount2 += newSummary.intAmount2;
        this.intAmount3 += newSummary.intAmount3;
        this.longAmount1 += newSummary.longAmount1;
        this.longAmount2 += newSummary.longAmount2;
        this.longAmount3 += newSummary.longAmount3;
        this.longDecimalAmount1 = this.longDecimalAmount1.add(newSummary.longDecimalAmount1);
        this.longDecimalAmount2 = this.longDecimalAmount2.add(newSummary.longDecimalAmount2);
        this.longDecimalAmount3 = this.longDecimalAmount3.add(newSummary.longDecimalAmount3);
        this.decimalAmount1 = this.decimalAmount1.add(newSummary.decimalAmount1);
        this.decimalAmount2 = this.decimalAmount2.add(newSummary.decimalAmount2);
        this.decimalAmount3 = this.decimalAmount3.add(newSummary.decimalAmount3);
    }

    @Override
    public void Multiply(int value) {
        // NOTE: connectedcallsCC is intentionally NOT multiplied (faithful to the C# source).
        this.totalcalls = value * this.totalcalls;
        this.connectedcalls = value * this.connectedcalls;
        this.successfulcalls = value * this.successfulcalls;
        this.actualduration = BigDecimal.valueOf(value).multiply(this.actualduration);
        this.roundedduration = BigDecimal.valueOf(value).multiply(this.roundedduration);
        this.duration1 = BigDecimal.valueOf(value).multiply(this.duration1);
        this.duration2 = BigDecimal.valueOf(value).multiply(this.duration2);
        this.duration3 = BigDecimal.valueOf(value).multiply(this.duration3);
        this.PDD = BigDecimal.valueOf(value).multiply(this.PDD);
        this.customercost = BigDecimal.valueOf(value).multiply(this.customercost);
        this.suppliercost = BigDecimal.valueOf(value).multiply(this.suppliercost);
        this.tax1 = BigDecimal.valueOf(value).multiply(this.tax1);
        this.tax2 = BigDecimal.valueOf(value).multiply(this.tax2);
        this.vat = BigDecimal.valueOf(value).multiply(this.vat);
        this.intAmount1 = value * this.intAmount1;
        this.intAmount2 = value * this.intAmount2;
        this.intAmount3 = value * this.intAmount3;
        this.longAmount1 = value * this.longAmount1;
        this.longAmount2 = value * this.longAmount2;
        this.longAmount3 = value * this.longAmount3;
        this.longDecimalAmount1 = BigDecimal.valueOf(value).multiply(this.longDecimalAmount1);
        this.longDecimalAmount2 = BigDecimal.valueOf(value).multiply(this.longDecimalAmount2);
        this.longDecimalAmount3 = BigDecimal.valueOf(value).multiply(this.longDecimalAmount3);
        this.decimalAmount1 = BigDecimal.valueOf(value).multiply(this.decimalAmount1);
        this.decimalAmount2 = BigDecimal.valueOf(value).multiply(this.decimalAmount2);
        this.decimalAmount3 = BigDecimal.valueOf(value).multiply(this.decimalAmount3);
    }

    @Override
    public AbstractCdrSummary CloneWithFakeId() {
        AbstractCdrSummary newSummary = new sum_voice_day_03(); // any concrete type; cast in caller
        newSummary.id = -1; // must set externally
        newSummary.tup_switchid = this.tup_switchid;
        newSummary.tup_inpartnerid = this.tup_inpartnerid;
        newSummary.tup_outpartnerid = this.tup_outpartnerid;
        newSummary.tup_incomingroute = this.tup_incomingroute;
        newSummary.tup_outgoingroute = this.tup_outgoingroute;
        newSummary.tup_customerrate = this.tup_customerrate;
        newSummary.tup_supplierrate = this.tup_supplierrate;
        newSummary.tup_incomingip = this.tup_incomingip;
        newSummary.tup_outgoingip = this.tup_outgoingip;
        newSummary.tup_countryorareacode = this.tup_countryorareacode;
        newSummary.tup_matchedprefixcustomer = this.tup_matchedprefixcustomer;
        newSummary.tup_matchedprefixsupplier = this.tup_matchedprefixsupplier;
        newSummary.tup_sourceId = this.tup_sourceId;
        newSummary.tup_destinationId = this.tup_destinationId;
        newSummary.tup_customercurrency = this.tup_customercurrency;
        newSummary.tup_suppliercurrency = this.tup_suppliercurrency;
        newSummary.tup_tax1currency = this.tup_tax1currency;
        newSummary.tup_tax2currency = this.tup_tax2currency;
        newSummary.tup_vatcurrency = this.tup_vatcurrency;
        newSummary.tup_starttime = this.tup_starttime;
        newSummary.totalcalls = this.totalcalls;
        newSummary.connectedcalls = this.connectedcalls;
        newSummary.connectedcallsCC = this.connectedcallsCC;
        newSummary.successfulcalls = this.successfulcalls;
        newSummary.actualduration = this.actualduration;
        newSummary.roundedduration = this.roundedduration;
        newSummary.duration1 = this.duration1;
        newSummary.duration2 = this.duration2;
        newSummary.duration3 = this.duration3;
        newSummary.PDD = this.PDD;
        newSummary.customercost = this.customercost;
        newSummary.suppliercost = this.suppliercost;
        newSummary.tax1 = this.tax1;
        newSummary.tax2 = this.tax2;
        newSummary.vat = this.vat;
        newSummary.intAmount1 = this.intAmount1;
        newSummary.intAmount2 = this.intAmount2;
        newSummary.intAmount3 = this.intAmount3;
        newSummary.longAmount1 = this.longAmount1;
        newSummary.longAmount2 = this.longAmount2;
        newSummary.longAmount3 = this.longAmount3;
        newSummary.longDecimalAmount1 = this.longDecimalAmount1;
        newSummary.longDecimalAmount2 = this.longDecimalAmount2;
        newSummary.longDecimalAmount3 = this.longDecimalAmount3;
        newSummary.decimalAmount1 = this.decimalAmount1;
        newSummary.decimalAmount2 = this.decimalAmount2;
        newSummary.decimalAmount3 = this.decimalAmount3;
        return newSummary;
    }

    // ---- ICacheble<AbstractCdrSummary>: the per-row SQL the cache writes, ported VERBATIM from the
    //      legacy EntityExtensions writers. "AbstractCdrSummary" is the table-name placeholder that
    //      CdrSummaryContext replaces with the concrete sum_voice_* table name. ----

    /** Column list for the INSERT header, in GetExtInsertValues order. */
    public static final String ExtInsertColumns =
            "id,tup_switchid,tup_inpartnerid,tup_outpartnerid,tup_incomingroute,tup_outgoingroute," +
            "tup_customerrate,tup_supplierrate,tup_incomingip,tup_outgoingip,tup_countryorareacode," +
            "tup_matchedprefixcustomer,tup_matchedprefixsupplier,tup_sourceId,tup_destinationId," +
            "tup_customercurrency,tup_suppliercurrency,tup_tax1currency,tup_tax2currency,tup_vatcurrency," +
            "tup_starttime,totalcalls,connectedcalls,connectedcallsCC,successfulcalls,actualduration," +
            "roundedduration,duration1,duration2,duration3,PDD,customercost,suppliercost,tax1,tax2,vat," +
            "intAmount1,intAmount2,longAmount1,longAmount2,longDecimalAmount1,longDecimalAmount2," +
            "intAmount3,longAmount3,longDecimalAmount3,decimalAmount1,decimalAmount2,decimalAmount3";

    @Override
    public StringBuilder GetExtInsertValues() {
        return new StringBuilder("(")
                .append(MySqlFieldExtensions.ToMySqlField(this.id)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_switchid)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_inpartnerid)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_outpartnerid)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_incomingroute)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_outgoingroute)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_customerrate)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_supplierrate)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_incomingip)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_outgoingip)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_countryorareacode)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_matchedprefixcustomer)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_matchedprefixsupplier)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_sourceId)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_destinationId)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_customercurrency)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_suppliercurrency)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_tax1currency)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_tax2currency)).append(",")
                .append(MySqlFieldExtensions.ToNotNullSqlField(this.tup_vatcurrency)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tup_starttime)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.totalcalls)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.connectedcalls)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.connectedcallsCC)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.successfulcalls)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.actualduration)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.roundedduration)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.duration1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.duration2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.duration3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.PDD)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.customercost)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.suppliercost)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tax1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.tax2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.vat)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.intAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.intAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.intAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount3)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.decimalAmount1)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.decimalAmount2)).append(",")
                .append(MySqlFieldExtensions.ToMySqlField(this.decimalAmount3)).append(")");
    }

    @Override
    public StringBuilder GetUpdateCommand(Function<AbstractCdrSummary, String> whereClauseMethod) {
        return new StringBuilder("update AbstractCdrSummary set\n"
                + "                totalcalls=" + MySqlFieldExtensions.ToMySqlField(this.totalcalls) + " ,\n"
                + "                connectedcalls=" + MySqlFieldExtensions.ToMySqlField(this.connectedcalls) + " ,\n"
                + "                connectedcallsCC=" + MySqlFieldExtensions.ToMySqlField(this.connectedcallsCC) + " ,\n"
                + "                successfulcalls=" + MySqlFieldExtensions.ToMySqlField(this.successfulcalls) + " ,\n"
                + "                actualduration=" + MySqlFieldExtensions.ToMySqlField(this.actualduration) + " ,\n"
                + "                roundedduration=" + MySqlFieldExtensions.ToMySqlField(this.roundedduration) + " ,\n"
                + "                duration1=" + MySqlFieldExtensions.ToMySqlField(this.duration1) + " ,\n"
                + "                duration2=" + MySqlFieldExtensions.ToMySqlField(this.duration2) + " ,\n"
                + "                duration3=" + MySqlFieldExtensions.ToMySqlField(this.duration3) + " ,\n"
                + "                PDD=" + MySqlFieldExtensions.ToMySqlField(this.PDD) + " ,\n"
                + "                customercost=" + MySqlFieldExtensions.ToMySqlField(this.customercost) + " ,\n"
                + "                suppliercost=" + MySqlFieldExtensions.ToMySqlField(this.suppliercost) + " ,\n"
                + "                tax1=" + MySqlFieldExtensions.ToMySqlField(this.tax1) + " ,\n"
                + "                tax2=" + MySqlFieldExtensions.ToMySqlField(this.tax2) + " ,\n"
                + "                vat=" + MySqlFieldExtensions.ToMySqlField(this.vat) + " ,\n"
                + "                intAmount1=" + MySqlFieldExtensions.ToMySqlField(this.intAmount1) + " ,\n"
                + "                intAmount2=" + MySqlFieldExtensions.ToMySqlField(this.intAmount2) + " ,\n"
                + "                longAmount1=" + MySqlFieldExtensions.ToMySqlField(this.longAmount1) + " ,\n"
                + "                longAmount2=" + MySqlFieldExtensions.ToMySqlField(this.longAmount2) + " ,\n"
                + "                longDecimalAmount1=" + MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount1) + " ,\n"
                + "                longDecimalAmount2=" + MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount2) + " ,\n"
                + "                intAmount3=" + MySqlFieldExtensions.ToMySqlField(this.intAmount3) + " ,\n"
                + "                longAmount3=" + MySqlFieldExtensions.ToMySqlField(this.longAmount3) + " ,\n"
                + "                longDecimalAmount3=" + MySqlFieldExtensions.ToMySqlField(this.longDecimalAmount3) + " ,\n"
                + "                decimalAmount1=" + MySqlFieldExtensions.ToMySqlField(this.decimalAmount1) + " ,\n"
                + "                decimalAmount2=" + MySqlFieldExtensions.ToMySqlField(this.decimalAmount2) + " ,\n"
                + "                decimalAmount3=" + MySqlFieldExtensions.ToMySqlField(this.decimalAmount3) + " \n"
                + "                " + whereClauseMethod.apply(this) + "\n"
                + "                ");
    }

    @Override
    public StringBuilder GetDeleteCommand(Function<AbstractCdrSummary, String> whereClauseMethod) {
        return new StringBuilder("delete from AbstractCdrSummary\n"
                + "                " + whereClauseMethod.apply(this) + "\n"
                + "                ");
    }
}
