package com.telcobright.billing.beans;

import com.telcobright.billing.data.MySqlCdrBatchRunner;
import com.telcobright.billing.data.PostgresEdge;
import com.telcobright.billing.data.PostgresTenantTables;
import com.telcobright.billing.ingest.CdrEventPreprocessor;
import com.telcobright.billing.ingest.IngestHealth;
import com.telcobright.billing.ingest.MultiTenantCdrBatch;
import com.telcobright.billing.ingest.MultiTenantCdrProcessor;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.MediationOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.testsupport.AdViewSamples;
import com.telcobright.billing.testsupport.PingTopicProducer;
import com.telcobright.billing.testsupport.PostgresLab;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rehearsal's finding (R-0001 §1.2), as one batch on PostgreSQL (LAB; skipped without {@code -Dbc.lab.pg.url}):
 * the ping's topic is NOT on the brokers. Before, the ingest's thread waited for it inside {@code send()} — 60 s per
 * tier per batch, behind a green health road and with no line of billing-core's own. Now the batch's rows are in
 * {@code cdr} at once, there is ONE WARN, and the health road is UP with the ping as a detail.
 */
class PingDoesNotHoldABatchLabTests {
    private static final Logger LOG = Logger.getLogger(PingDoesNotHoldABatchLabTests.class);
    private static final String Root = "bct_ping_btcl";
    private static final String Reseller = "bct_ping_res_44";
    private static final String PingTopic = "cdr_summary_ping_btcl";
    private static final String Brokers = "127.0.0.1:7792";

    /** The brief's sample view (two tiers), with the tiers named after this test's schemas. */
    private static final String TheView = AdViewSamples.ONE_VIEW_TWO_TIERS.replace("res_44", Reseller).replace("btcl", Root);

    private final PingTopicProducer kafka = new PingTopicProducer();
    private SummaryChangeNotificationPublisher ping;

    @AfterEach
    void stopAndDrop() {
        kafka.LetGo();
        if (ping == null) return;
        ping.dispose();
        PostgresLab.DropSchema(Root);
        PostgresLab.DropSchema(Reseller);
    }

    @Test
    void with_the_pings_topic_missing_the_rows_are_in_cdr_at_once_with_one_warn_and_a_health_road_that_is_up() {
        PostgresLab.Required();
        PostgresLab.FreshTenantSchema(Root);
        PostgresLab.FreshTenantSchema(Reseller);
        kafka.topicIsThere = false;
        kafka.HoldEveryCall();                       // every call to the brokers waits, as for a topic that is not there
        IngestHealth health = new IngestHealth();
        List<String> warns = new CopyOnWriteArrayList<>();
        ping = new SummaryChangeNotificationPublisher(kafka, kafka::IsThere, null, PingTopic, Brokers, health, warns::add,
                System::currentTimeMillis);
        MultiTenantCdrProcessor ingest = IngestWithThePingOn(health);
        MultiTenantCdrBatch batch = new CdrEventPreprocessor(AdViewSamples.registryWith(Root, Reseller)).Preprocess(List.of(TheView));

        long began = System.nanoTime();
        MultiTenantCdrProcessor.Result written = ingest.Process(batch);
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertEquals(2, written.committed(), "both tiers");
        assertEquals(1L, PostgresLab.Count(Root + ".cdr"));
        assertEquals(1L, PostgresLab.Count(Reseller + ".cdr"));
        assertTrue(tookMs < PingTopicProducer.LongestHoldMs / 2, "both tiers were written in " + tookMs
                + " ms while the brokers were still being asked for the ping's topic: a tier that waited for it"
                + " would have taken about " + PingTopicProducer.LongestHoldMs + " ms");
        LOG.infof("two tiers written in %d ms with the ping's topic not answering", tookMs);

        kafka.LetGo();                               // the brokers answer: the topic is not there
        Eventually(() -> ping.NotSentSinceTheLastLine() == 2 && warns.size() == 1, "one WARN; the two tiers' pings counted");
        assertEquals(1, warns.size(), "ONE line of billing-core's own");
        assertTrue(warns.get(0).contains("topic '" + PingTopic + "' on " + Brokers), warns.get(0));
        HealthCheckResponse answer = new CdrIngestHealthCheck(health).call();
        assertEquals(HealthCheckResponse.Status.UP, answer.getStatus(), "the rows are written; the summary service polls");
        assertTrue(String.valueOf(answer.getData().orElseThrow().get(CdrIngestHealthCheck.PingDetailKey)).contains(PingTopic));
    }

    /** The real write path of the ingest, with the summary ping switched ON. */
    private MultiTenantCdrProcessor IngestWithThePingOn(IngestHealth health) {
        ITenantRegistry registry = AdViewSamples.registryWith(Root, Reseller);
        SummaryOutboxOptions pingOn = new SummaryOutboxOptions();
        pingOn.Enabled = true;
        var runner = MySqlCdrBatchRunner.On(new PostgresEdge(new PostgresTenantTables(PostgresTenantTables.Options.Defaults())));
        var processor = new CdrProcessor(registry, PostgresLab.Factory(), runner, pingOn, ping, new CdrIngestOptions(),
                new MediationOptions(), health, null);
        return new MultiTenantCdrProcessor(processor, LOG);
    }

    private static void Eventually(java.util.function.BooleanSupplier seen, String what) {
        long giveUpAt = System.nanoTime() + 5_000_000_000L;
        while (!seen.getAsBoolean()) {
            assertTrue(System.nanoTime() < giveUpAt, "not seen within 5 s: " + what);
            try { Thread.sleep(10); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }
}
