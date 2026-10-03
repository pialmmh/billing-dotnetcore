package com.telcobright.billing.ingest;

import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.beans.SummaryChangeNotificationPublisher;
import com.telcobright.billing.data.MySqlCdrBatchRunner;
import com.telcobright.billing.data.PostgresEdge;
import com.telcobright.billing.data.PostgresTenantTables;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.MediationOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.testsupport.AdViewSamples;
import com.telcobright.billing.testsupport.PostgresLab;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOT a test of the suite — its name keeps surefire away from it. It LEAVES two tier schemas behind in the lab
 * database, {@code btcl} and {@code res_44}, made and filled by billing-core's own code (the wire's records through
 * the real ingest write path), so that another service's reader — ad-sphere's report roads — can be pointed at
 * tables billing-core really made. Run it by name; it drops and re-makes the two schemas each time:
 *
 * <pre>
 *   mvn -f java/pom.xml test -Dtest=LabSchemaForTheReportRoads -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dbc.lab.pg.url=jdbc:postgresql://127.0.0.1:7743/routesphere
 * </pre>
 *
 * What it writes: the brief's sample view (two tiers), 40 more shown views over two days (three campaigns, two
 * zones, two apps, two advertisers; every fourth one paid in units), a view admitted and never shown (charged), two
 * views nobody was admitted for (zero), and one record with no partner (it goes to {@code cdrerror}).
 */
class LabSchemaForTheReportRoads {
    private static final Logger LOG = Logger.getLogger(LabSchemaForTheReportRoads.class);
    private static final String Root = "btcl";
    private static final String Reseller = "res_44";
    private static final DateTimeFormatter WallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[][] Campaigns = {{"12", "cola-eid"}, {"13", "tea-winter"}, {"21", "bank-app"}};
    private static final String[] Zones = {"dhaka-north", "sylhet-01"};
    private static final String[] Apps = {"captive", "portal"};

    @Test
    void two_tier_schemas_with_ad_views_written_by_billing_core() {
        PostgresLab.Required();
        PostgresLab.FreshTenantSchema(Root);
        PostgresLab.FreshTenantSchema(Reseller);
        ITenantRegistry registry = AdViewSamples.registryWith(Root, Reseller);
        var noPing = new SummaryOutboxOptions();
        var processor = new CdrProcessor(registry, PostgresLab.Factory(),
                MySqlCdrBatchRunner.On(new PostgresEdge(new PostgresTenantTables(PostgresTenantTables.Options.Defaults()))),
                noPing, new SummaryChangeNotificationPublisher(noPing), new CdrIngestOptions(), new MediationOptions(), new IngestHealth());
        var preprocessor = new CdrEventPreprocessor(registry);
        var writer = new MultiTenantCdrProcessor(processor, LOG);

        List<String> poll = new ArrayList<>();
        poll.add(AdViewSamples.ONE_VIEW_TWO_TIERS);
        for (int i = 1; i <= 40; i++) poll.add(AShownView(i));
        poll.add(AViewAdmittedAndNeverShown());
        poll.add(ARefusedView("r001", "2026-10-02 21:20:00", "NO_RULE"));
        poll.add(ARefusedView("r002", "2026-10-03 09:05:00", "INSUFFICIENT_BALANCE"));
        poll.add(ARefusedView("r003", "2026-10-03 09:06:00", "NO_RULE").replace("\"inPartnerId\": 2, ", ""));   // no partner: cdrerror

        MultiTenantCdrBatch batch = preprocessor.Preprocess(poll);
        assertTrue(batch.deadLetters().isEmpty(), batch.deadLetters().toString());
        MultiTenantCdrProcessor.Result result = writer.Process(batch);

        assertEquals(2, result.committed());
        assertEquals(42L, PostgresLab.Count(Reseller + ".cdr"), "the sample + 40 shown + the never-shown, at the leaf");
        assertEquals(44L, PostgresLab.Count(Root + ".cdr"), "the same 42 at the root, and two refused views");
        assertEquals(1L, PostgresLab.Count(Root + ".cdrerror"));
        LOG.infof("LEFT IN THE LAB: schemas %s and %s of database %s on %s:%d — cdr %d / %d rows, acc_chargeable %d / %d, cdrerror %d",
                Root, Reseller, PostgresLab.Database(), PostgresLab.Host(), PostgresLab.Port(), PostgresLab.Count(Root + ".cdr"),
                PostgresLab.Count(Reseller + ".cdr"), PostgresLab.Count(Root + ".acc_chargeable"),
                PostgresLab.Count(Reseller + ".acc_chargeable"), PostgresLab.Count(Root + ".cdrerror"));
    }

    /** The sample's view, as view number {@code i}: its own id, its own time, a campaign, a zone, an app, a payer. */
    private static String AShownView(int i) {
        String[] campaign = Campaigns[i % Campaigns.length];
        String zone = Zones[i % Zones.length];
        LocalDateTime start = LocalDateTime.of(2026, 10, 2, 8, 0, 0).plusMinutes(37L * i);
        String id = String.format("2f6c1c1e-7a52-4d0b-9c7e-%012d", i);
        String view = AdViewSamples.ONE_VIEW_TWO_TIERS
                .replace(AdViewSamples.VIEW_ID, id)
                .replace("2026-10-02 21:14:03", start.format(WallClock))
                .replace("2026-10-02 21:14:04", start.plusSeconds(1).format(WallClock))
                .replace("2026-10-02 21:14:14", start.plusSeconds(1 + 5 + i % 6).format(WallClock))
                .replace("\"durationSec\": 10,", "\"durationSec\": " + (5 + i % 6) + ",")
                .replace("\"campaignId\":12", "\"campaignId\":" + campaign[0])
                .replace("\"campaignName\":\"cola-eid\"", "\"campaignName\":\"" + campaign[1] + "\"")
                .replace("\"incomingRoute\": \"cola-eid\"", "\"incomingRoute\": \"" + campaign[1] + "\"")
                .replace("dhaka-north", zone)
                .replace("\"app\":\"captive\"", "\"app\":\"" + Apps[i % Apps.length] + "\"")
                .replace("\"inPartnerId\": 1,", "\"inPartnerId\": " + (i % 5 == 0 ? 7 : 1) + ",")
                .replace("8801711000001", String.format("88017110%05d", i));
        return i % 4 == 0 ? PaidInUnits(view) : view;
    }

    /** The leaf pays one package unit instead of money (the root tier keeps its money). */
    private static String PaidInUnits(String view) {
        return view.replace("\"callRatePerMinBDT\": 0.50, \"inPartnerUom\": \"BDT\", \"idPackageAccount\": 3061,",
                        "\"callRatePerMinBDT\": 1, \"inPartnerUom\": \"OTH_ea\", \"idPackageAccount\": 3062,")
                .replace("\"inPartnerCost\": 0.50, \"packageAmount\": 0,", "\"inPartnerCost\": 0, \"packageAmount\": 1,");
    }

    private static String AViewAdmittedAndNeverShown() {
        return AdViewSamples.ONE_VIEW_TWO_TIERS
                .replace(AdViewSamples.VIEW_ID, "2f6c1c1e-7a52-4d0b-9c7e-00000000ns01")
                .replace("\"answerTime\": \"2026-10-02 21:14:04\"", "\"answerTime\": null")
                .replace("2026-10-02 21:14:03", "2026-10-03 10:00:00")
                .replace("2026-10-02 21:14:14", "2026-10-03 10:00:30")
                .replace("\"durationSec\": 10,", "\"durationSec\": 0,")
                .replace("NORMAL_CLEARING", "NOT_SHOWN")
                .replace("\"completed\":true,\"credited\":true", "\"completed\":false,\"credited\":false");
    }

    private static String ARefusedView(String suffix, String at, String cause) {
        return AdViewSamples.A_REFUSED_VIEW
                .replace("r001", suffix)
                .replace("2026-10-02 21:20:00", at)
                .replace("NO_RULE", cause);
    }
}
