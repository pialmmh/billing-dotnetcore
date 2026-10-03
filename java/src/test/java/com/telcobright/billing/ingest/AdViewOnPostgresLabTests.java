package com.telcobright.billing.ingest;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.beans.SummaryChangeNotificationPublisher;
import com.telcobright.billing.data.MySqlCdrBatchRunner;
import com.telcobright.billing.data.PostgresEdge;
import com.telcobright.billing.data.PostgresTenantTables;
import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.validation.InPartnerIdGt0;
import com.telcobright.billing.mediation.validation.OutPartnerIdGt0;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.MediationOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.testsupport.AdViewSamples;
import com.telcobright.billing.testsupport.PostgresLab;
import com.telcobright.billing.testsupport.TestData;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ad view end to end on PostgreSQL (LAB; skipped without {@code -Dbc.lab.pg.url}): the wire's own records go
 * through the REAL ingest write path — the preprocessor, {@link MultiTenantCdrProcessor}, {@link CdrProcessor}, the
 * batch runner on the PostgreSQL edge — into two tier schemas made the way prime-context makes them, EMPTY of
 * billing-core's tables. The "done when" of the brief, as rows:
 * <ul>
 *   <li><b>B3</b> — the sample's two tiers give two cdr rows and two chargeables with the sample's amounts;</li>
 *   <li><b>B4</b> — a failed view passes; a record with no partner goes to cdrerror;</li>
 *   <li><b>B5</b> — the same message twice = the same rows as once, in every tier's schema, and one outbox share;</li>
 *   <li><b>B7</b> — a new, empty tenant schema takes its first batch;</li>
 *   <li><b>B9</b> — the outbox row is the call's: entity cdr, op add, one row per tenant batch.</li>
 * </ul>
 */
class AdViewOnPostgresLabTests {
    private static final Logger LOG = Logger.getLogger(AdViewOnPostgresLabTests.class);
    private static final String Root = "bct_btcl";
    private static final String Reseller = "bct_res_44";

    /** The brief's sample, with the two tiers named after this test's schemas. */
    private static String InTheLabsTiers(String wireValue) {
        return wireValue.replace("res_44", Reseller).replace("btcl", Root);
    }

    private static final String TheView = InTheLabsTiers(AdViewSamples.ONE_VIEW_TWO_TIERS);
    private static final String ARefusedView = InTheLabsTiers(AdViewSamples.A_REFUSED_VIEW);
    private static final String ARecordWithNoPartner = ARefusedView.replace("\"inPartnerId\": 2, ", "");

    private Tenant root, reseller;
    private ITenantRegistry registry;
    private CdrEventPreprocessor preprocessor;
    private MultiTenantCdrProcessor writer;
    private CdrProcessor processor;

    @BeforeEach
    void twoEmptyTierSchemas() {
        PostgresLab.Required();
        PostgresLab.FreshTenantSchema(Root);
        PostgresLab.FreshTenantSchema(Reseller);
        registry = AdViewSamples.registryWith(Root, Reseller);
        root = registry.FindByDbName(Root);
        reseller = registry.FindByDbName(Reseller);
        var runner = MySqlCdrBatchRunner.On(new PostgresEdge(new PostgresTenantTables(PostgresTenantTables.Options.Defaults())));
        var noPing = new SummaryOutboxOptions();          // Enabled = false: the ping is another test's
        processor = new CdrProcessor(registry, PostgresLab.Factory(), runner, noPing,
                new SummaryChangeNotificationPublisher(noPing), new CdrIngestOptions(), new MediationOptions(), new IngestHealth());
        preprocessor = new CdrEventPreprocessor(registry);
        writer = new MultiTenantCdrProcessor(processor, LOG);
    }

    @AfterEach
    void drop() {
        if (registry == null) return;
        PostgresLab.DropSchema(Root);
        PostgresLab.DropSchema(Reseller);
    }

    /** One poll-batch of wire values through the ingest's write path. */
    private MultiTenantCdrProcessor.Result Ingest(String... wireValues) {
        MultiTenantCdrBatch batch = preprocessor.Preprocess(List.of(wireValues));
        assertTrue(batch.deadLetters().isEmpty(), "no dead letter expected: " + batch.deadLetters());
        return writer.Process(batch);
    }

    private static String One(String schema, String sql) {
        return PostgresLab.Scalar(sql.replace("{s}", schema));
    }

    private static void assertRows(String schema, long cdr, long cdrerror, long chargeables, long outbox) {
        assertEquals(cdr, PostgresLab.Count(schema + ".cdr"), schema + ".cdr");
        assertEquals(cdrerror, PostgresLab.Count(schema + ".cdrerror"), schema + ".cdrerror");
        assertEquals(chargeables, PostgresLab.Count(schema + ".acc_chargeable"), schema + ".acc_chargeable");
        assertEquals(outbox, PostgresLab.Count(schema + ".summary_affected"), schema + ".summary_affected");
    }

    // ── B3 / B7: the sample, in two empty schemas ────────────────────────────────────────────────────────────

    @Test
    void the_samples_two_tiers_give_a_cdr_row_and_a_chargeable_in_each_tiers_own_schema_with_the_samples_amounts() {
        MultiTenantCdrProcessor.Result result = Ingest(TheView);

        assertEquals(2, result.committed());
        assertRows(Reseller, 1, 0, 1, 1);
        assertRows(Root, 1, 0, 1, 1);

        // the leaf: the reseller's client pays 0.50
        assertEquals("30|1|0.50000000|0.50000000|70|BDT|3061|0.00000000|NORMAL_CLEARING|cola-eid|dhaka-north|1|10.00000000",
                One(Reseller, "select concat_ws('|', servicegroup, inpartnerid, inpartnercost, customerrate, matchedprefixcustomer,"
                        + " inpartneruom, idpackageaccount, packageamount, hangupcause, incomingroute, outgoingroute, chargingstatus,"
                        + " durationsec) from {s}.cdr"));
        assertEquals(AdViewSamples.VIEW_ID + "|" + AdViewSamples.VIEW_ID + "|" + Root + " > " + Reseller + "|kafka:cdr",
                One(Reseller, "select concat_ws('|', channelcalluuid, uniquebillid, resellerhierarchy, filename) from {s}.cdr"));
        assertEquals("2026-10-02 21:14:03|2026-10-02 21:14:04|2026-10-02 21:14:14",
                One(Reseller, "select concat_ws('|', starttime, answertime, endtime) from {s}.cdr"));
        assertEquals("{\"campaignId\":12,\"campaignName\":\"cola-eid\",\"contentId\":\"c-81\",\"app\":\"captive\",\"ruleId\":3,"
                        + "\"zone\":\"dhaka-north\",\"site\":\"mirpur-10\",\"requiredSeconds\":10,\"completed\":true,\"credited\":true,"
                        + "\"fallback\":false,\"levelIndex\":0,\"partnerName\":\"Unilever\",\"balanceBefore\":120.00,\"balanceAfter\":119.50,"
                        + "\"reserveRef\":\"" + AdViewSamples.VIEW_ID + "#L0\"}",
                One(Reseller, "select additionalmetadata from {s}.cdr"), "character for character");
        assertEquals("30|30|1|0.50000000|BDT|0.50000000|70|10.00000000|TF_s|0|0|" + AdViewSamples.VIEW_ID,
                One(Reseller, "select concat_ws('|', servicegroup, servicefamily, assigneddirection, billedamount, idbilleduom,"
                        + " unitpriceorcharge, prefix, quantity, idquantityuom, glaccountid, rateid, uniquebillid) from {s}.acc_chargeable"));
        assertEquals("t", One(Reseller, "select (select idevent from {s}.acc_chargeable) = (select idcall from {s}.cdr)"),
                "the chargeable points at its cdr");
        assertEquals("2026-10-02 21:14:03", One(Reseller, "select transactiontime::text from {s}.acc_chargeable"));

        // the root: the reseller pays the operator 0.40
        assertEquals("30|44|0.40000000|0.40000000|3044|" + Root,
                One(Root, "select concat_ws('|', servicegroup, inpartnerid, inpartnercost, customerrate, idpackageaccount,"
                        + " resellerhierarchy) from {s}.cdr"));
        assertEquals("0.40000000|BDT|0.40000000", One(Root, "select concat_ws('|', billedamount, idbilleduom, unitpriceorcharge) from {s}.acc_chargeable"));
    }

    // ── B4: a failed view passes; no partner = cdrerror ──────────────────────────────────────────────────────

    @Test
    void a_refused_view_is_a_cdr_row_and_a_chargeable_of_zero_and_a_record_with_no_partner_goes_to_cdrerror() {
        Ingest(ARefusedView);
        Ingest(ARecordWithNoPartner.replace("r001", "r777"));

        assertRows(Root, 1, 1, 1, 1);
        assertEquals("30|0|NO_RULE|0.00000000|2", One(Root, "select concat_ws('|', servicegroup, chargingstatus, hangupcause, inpartnercost, inpartnerid) from {s}.cdr"));
        assertEquals("t", One(Root, "select answertime is null and customerrate is null and inpartneruom is null from {s}.cdr"),
                "what the wire did not send is NULL, not invented");
        assertEquals("0.00000000|BDT|0.00000000", One(Root, "select concat_ws('|', billedamount, idbilleduom, unitpriceorcharge) from {s}.acc_chargeable"),
                "a chargeable of zero, in money");
        assertEquals("InPartnerId must be > 0|30|NO_RULE", One(Root, "select concat_ws('|', errorcode, servicegroup, hangupcause) from {s}.cdrerror"));
    }

    // ── B5: idempotency on (the tier's schema, ChannelCallUuid) ──────────────────────────────────────────────

    @Test
    void the_same_message_twice_is_the_same_rows_as_once_in_every_tiers_schema_and_one_outbox_share() {
        Ingest(TheView);
        String rowsOnce = RowsOf(Reseller) + RowsOf(Root);

        MultiTenantCdrProcessor.Result again = Ingest(TheView);

        assertEquals(0, again.rated() + again.errored(), "dropped before mediation, in both tiers");
        assertRows(Reseller, 1, 0, 1, 1);
        assertRows(Root, 1, 0, 1, 1);
        assertEquals(rowsOnce, RowsOf(Reseller) + RowsOf(Root), "the very same rows");
    }

    private static String RowsOf(String schema) {
        return One(schema, "select concat_ws('|', (select string_agg(idcall::text || ':' || channelcalluuid, ',') from {s}.cdr),"
                + " (select string_agg(id::text || ':' || billedamount, ',') from {s}.acc_chargeable),"
                + " (select string_agg(id::text || ':' || md5(data), ',') from {s}.summary_affected))");
    }

    @Test
    void two_copies_of_a_call_in_one_poll_are_written_once() {
        MultiTenantCdrProcessor.Result result = Ingest(TheView, TheView);

        assertEquals(2, result.rated(), "one per tier");
        assertRows(Reseller, 1, 0, 1, 1);
        assertRows(Root, 1, 0, 1, 1);
    }

    @Test
    void a_rewind_after_the_first_tiers_commit_writes_the_first_tier_once() {
        Ingest(ARefusedView);                                           // the root's tables exist, its outbox is at id 1
        PostgresLab.AsAdmin("ALTER TABLE " + Root + ".summary_affected ADD CONSTRAINT bct_no_more CHECK (id < 2)");

        // the leaf tier commits, then the root's batch fails: the poll-batch is not committed and will be read again
        assertThrows(IllegalStateException.class, () -> Ingest(TheView));
        assertRows(Reseller, 1, 0, 1, 1);
        assertRows(Root, 1, 0, 1, 1);                                   // only the refused view

        PostgresLab.AsAdmin("ALTER TABLE " + Root + ".summary_affected DROP CONSTRAINT bct_no_more");
        MultiTenantCdrProcessor.Result redelivered = Ingest(TheView);

        assertEquals(1, redelivered.rated(), "only the root's record is mediated; the leaf's is already written");
        assertRows(Reseller, 1, 0, 1, 1);                               // once
        assertRows(Root, 2, 0, 2, 2);
    }

    @Test
    void a_record_that_already_sits_in_cdrerror_is_not_written_again() {
        Ingest(ARecordWithNoPartner);

        MultiTenantCdrProcessor.Result again = Ingest(ARecordWithNoPartner);

        assertEquals(0, again.rated() + again.errored());
        assertRows(Root, 0, 1, 0, 0);
    }

    @Test
    void two_calls_that_share_a_bill_id_are_two_records_the_key_is_the_call_uuid() {
        String anotherCallSameBillId = ARefusedView.replace("\"channelCallUuid\": \"9d1e0f0a-0000-4000-8000-00000000r001\"",
                "\"channelCallUuid\": \"9d1e0f0a-0000-4000-8000-00000000r002\"");

        Ingest(ARefusedView);
        // one poll each: within one batch the pipeline's own guard refuses two rows with one bill id
        Ingest(anotherCallSameBillId);

        assertRows(Root, 2, 0, 2, 2);
    }

    // ── B9: the outbox row ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_outbox_row_is_the_calls_one_row_a_tenant_batch_with_the_ad_views_facts_in_the_blob() {
        Ingest(TheView, ARefusedView);              // the root gets two records in one batch: ONE outbox row

        assertEquals("cdr|add", One(Root, "select concat_ws('|', entity_type, op) from {s}.summary_affected"));
        assertEquals(1L, PostgresLab.Count(Root + ".summary_affected"));
        List<Entry> blob = SummaryOutboxWriter.Decode(One(Root, "select data from {s}.summary_affected"));
        assertEquals(2, blob.size());
        Entry shown = blob.stream().filter(e -> e.Cdr().AnswerTime != null).findFirst().orElseThrow();
        assertEquals(30, shown.Cdr().ServiceGroup);
        assertEquals("NORMAL_CLEARING", shown.Cdr().HangupCause);
        assertTrue(shown.Cdr().AdditionalMetaData.startsWith("{\"campaignId\":12,\"levelIndex\":1,"), shown.Cdr().AdditionalMetaData);
        assertEquals(1, shown.Chargeables().size());
        assertEquals(30, shown.Customer().servicegroup);
    }

    // ── the report road's read, as ad_sphere ─────────────────────────────────────────────────────────────────

    @Test
    void the_reader_role_reads_the_view_back_with_the_report_roads_own_query_and_casts() throws SQLException {
        Ingest(TheView);

        // ad-sphere's JdbcCdrReader: its column list, its WHERE, its ORDER BY, and the three casts it makes.
        String sql = "SELECT IdCall, ChannelCallUuid, InPartnerId, OutPartnerId, OriginatingCallingNumber, OriginatingCalledNumber, Codec,"
                + " StartTime, AnswerTime, EndTime, DurationSec, HangupCause, InPartnerCost, InPartnerUom, PackageAmount, CustomerRate,"
                + " MatchedPrefixCustomer, IdPackageAccount, ResellerHierarchy, AdditionalMetaData FROM " + Reseller + ".cdr"
                + " WHERE ServiceGroup = 30 AND InPartnerId = ? AND StartTime >= ? AND StartTime < ? AND HangupCause = ? AND OutgoingRoute = ?"
                + " AND (AdditionalMetaData LIKE ? OR AdditionalMetaData LIKE ?) ORDER BY StartTime DESC, IdCall DESC LIMIT 20 OFFSET 0";
        try (Connection c = PostgresLab.As(PostgresLab.ReaderRole, null); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, 1);
            ps.setObject(2, Timestamp.valueOf("2026-10-02 00:00:00"));
            ps.setObject(3, Timestamp.valueOf("2026-10-03 00:00:00"));
            ps.setObject(4, "NORMAL_CLEARING");
            ps.setObject(5, "dhaka-north");
            ps.setObject(6, "%\"campaignId\":12,%");
            ps.setObject(7, "%\"campaignId\":12}%");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the view is found");
                assertEquals(1, (Integer) rs.getObject("InPartnerId"));
                assertEquals(9, (Integer) rs.getObject("OutPartnerId"));
                assertEquals(3061L, (Long) rs.getObject("IdPackageAccount"));
                assertEquals(AdViewSamples.VIEW_ID, rs.getString("ChannelCallUuid"));
                assertEquals(java.time.LocalDateTime.of(2026, 10, 2, 21, 14, 3), rs.getTimestamp("StartTime").toLocalDateTime());
                assertEquals(10, rs.getBigDecimal("DurationSec").intValue());
                assertEquals(0, new java.math.BigDecimal("0.50").compareTo(rs.getBigDecimal("InPartnerCost")));
                assertTrue(rs.getLong("IdCall") > 0);
                assertFalse(rs.next());
            }
        }
    }

    // ── the reprocess road keeps the wire's columns ──────────────────────────────────────────────────────────

    @Test
    void a_record_reprocessed_out_of_cdrerror_moves_to_cdr_with_the_wires_columns() {
        // The tenant first serves a stricter group 30 (an out-partner even on a refused view): the view goes to cdrerror.
        Map<Integer, ServiceGroupConfiguration> strict = Map.of(30, new ServiceGroupConfiguration(30, false,
                List.<Rule>of(new RatingRule(30, 1, null)), List.of(new InPartnerIdGt0()), List.of(new InPartnerIdGt0(), new OutPartnerIdGt0())));
        root.Context = new DynamicContext();
        root.Context.MediationContext = TestData.fixture().mediation(null, null, strict, null);
        Ingest(ARefusedView);
        assertRows(Root, 0, 1, 0, 0);

        root.Context.MediationContext = MediationContext.Empty;        // the configuration is put right; an operator reprocesses
        CdrProcessingResult reprocessed = processor.ReprocessErrors(Root, null, false, 100);

        assertTrue(reprocessed.Committed(), reprocessed.Error());
        assertRows(Root, 1, 0, 1, 1);
        assertEquals(AdViewSamples.REFUSED_VIEW_ID + "|NO_RULE|" + Root + "|30",
                One(Root, "select concat_ws('|', channelcalluuid, hangupcause, resellerhierarchy, servicegroup) from {s}.cdr"));
    }
}
