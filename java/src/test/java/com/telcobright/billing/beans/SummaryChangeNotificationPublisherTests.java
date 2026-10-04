package com.telcobright.billing.beans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.ingest.IngestHealth;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.testsupport.PingTopicProducer;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summary ping never holds a batch (the rehearsal's finding R-0001 §1.2: with the ping's topic missing the
 * ingest waited 60 s per tier per batch, with a green health road and no line of billing-core's own).
 * <ul>
 *   <li>a ping is handed to the ping's own thread; the caller goes on at once, whatever the brokers do;</li>
 *   <li>a ping that cannot be published is ONE WARN a minute that names the topic and the brokers;</li>
 *   <li>the health road stays UP and carries it as a detail, until the topic takes pings again.</li>
 * </ul>
 */
class SummaryChangeNotificationPublisherTests {
    private static final String Topic = "cdr_summary_ping_btcl";
    private static final String Brokers = "10.0.0.20:9092";
    private static final ObjectMapper Json = new ObjectMapper();

    private final PingTopicProducer kafka = new PingTopicProducer();
    private final IngestHealth health = new IngestHealth();
    private final List<String> warns = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private SummaryChangeNotificationPublisher ping;

    private void Started() {
        ping = new SummaryChangeNotificationPublisher(kafka, kafka::IsThere, null, Topic, Brokers, health, warns::add, clock::get);
    }

    @AfterEach
    void stop() {
        kafka.LetGo();
        if (ping != null) ping.dispose();
    }

    /** Wait (at most 5 s) for what the ping's own thread does. */
    private static void Eventually(BooleanSupplier seen, String what) {
        long giveUpAt = System.nanoTime() + 5_000_000_000L;
        while (!seen.getAsBoolean()) {
            assertTrue(System.nanoTime() < giveUpAt, "not seen within 5 s: " + what);
            try { Thread.sleep(10); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }

    // ── the caller never waits ───────────────────────────────────────────────────────────────────────────────

    @Test
    void the_caller_goes_on_at_once_while_the_brokers_are_asked_for_a_topic_that_is_not_there() {
        kafka.topicIsThere = false;
        kafka.HoldEveryCall();                                   // a call to the brokers waits, as for a missing topic
        Started();

        long began = System.nanoTime();
        for (String tier : List.of("btcl", "res_44", "res_44_7")) ping.Publish(tier, "cdr", 202);
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertTrue(tookMs < 1_000, "three tiers' pings were handed over in " + tookMs + " ms — not 3 × the brokers' wait");
    }

    @Test
    void the_brokers_are_only_ever_called_on_the_pings_own_thread() {
        Started();

        ping.Publish("btcl", "cdr", 1);

        Eventually(() -> kafka.taken.size() == 1, "the ping on its topic");
        assertEquals(Set.of("summary-ping"), kafka.callers);
    }

    @Test
    void a_ping_reaches_its_topic_with_the_tenant_the_entity_and_the_rows() throws Exception {
        Started();

        ping.Publish("res_44", "cdr", 3);

        Eventually(() -> kafka.taken.size() == 1, "the ping on its topic");
        assertEquals(Topic, kafka.taken.get(0).topic());
        JsonNode said = Json.readTree(kafka.taken.get(0).value());
        assertEquals("res_44", said.get("tenant").asText());
        assertEquals("cdr", said.get("entity").asText());
        assertEquals(3, said.get("rows").asInt());
        assertTrue(warns.isEmpty(), warns.toString());
        assertEquals("", health.PingDetail());
    }

    // ── a ping that cannot be published is said: one WARN a minute ───────────────────────────────────────────

    @Test
    void a_topic_that_is_not_there_is_one_warn_a_minute_that_names_the_topic_and_the_brokers() {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> warns.size() == 1, "the WARN of the start: the topic is asked for before the first batch");

        for (int batch = 0; batch < 20; batch++) ping.Publish("btcl", "cdr", 1);
        Eventually(() -> ping.NotSentSinceTheLastLine() == 20, "twenty pings counted as not sent");

        assertEquals(1, warns.size(), "twenty more pings in the same minute: still ONE line");
        assertTrue(warns.get(0).startsWith("summary ping NOT published to topic '" + Topic + "' on " + Brokers
                + " — the topic is not on the brokers."), warns.get(0));
        assertTrue(warns.get(0).contains("Nothing is lost"), warns.get(0));

        clock.addAndGet(SummaryChangeNotificationPublisher.WarnEveryMs);
        Eventually(() -> warns.size() == 2, "the next minute's line");
        assertTrue(warns.get(1).contains("(20 ping(s) not sent since the last line)"), warns.get(1));
        assertTrue(warns.get(1).contains(Topic) && warns.get(1).contains(Brokers), warns.get(1));
    }

    @Test
    void a_ping_the_brokers_refuse_in_the_middle_of_a_run_is_said_too() {
        Started();
        ping.Publish("btcl", "cdr", 1);
        Eventually(() -> kafka.taken.size() == 1, "a first ping on its topic");

        kafka.topicIsThere = false;                              // the topic is gone; the producer says so in its callback
        ping.Publish("btcl", "cdr", 1);

        Eventually(() -> warns.size() == 1, "the WARN");
        assertTrue(warns.get(0).contains("(1 ping(s) not sent since the last line)"), warns.get(0));
        assertTrue(health.PingDetail().contains(Topic), health.PingDetail());
    }

    @Test
    void while_the_topic_does_not_take_pings_they_are_dropped_untried_and_the_topic_is_asked_once_a_minute() {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> kafka.asked.get() == 1, "the topic asked for at start");

        for (int batch = 0; batch < 10; batch++) ping.Publish("btcl", "cdr", 1);
        Eventually(() -> ping.NotSentSinceTheLastLine() == 10, "ten pings counted as not sent");

        assertEquals(0, kafka.sends.get(), "no ping was handed to a producer whose brokers do not have the topic");
        assertEquals(0, kafka.producerAskedForTheTopic.get(), "and the producer was never asked for it: it would keep asking");
        assertEquals(1, kafka.asked.get(), "the brokers were not asked again inside the minute");

        clock.addAndGet(SummaryChangeNotificationPublisher.AskAgainEveryMs);
        Eventually(() -> kafka.asked.get() == 2, "asked again when the minute is over, without waiting for a batch");
    }

    @Test
    void a_ping_that_arrives_when_the_minute_is_over_makes_the_brokers_be_asked_first_and_goes_only_if_the_topic_is_there() {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> kafka.asked.get() == 1, "the topic asked for at start");
        kafka.HoldEveryCall();                                   // so that only the ping, not the idle tick, asks next
        clock.addAndGet(SummaryChangeNotificationPublisher.AskAgainEveryMs);
        kafka.topicIsThere = true;
        ping.Publish("btcl", "cdr", 7);
        kafka.LetGo();

        Eventually(() -> kafka.taken.size() == 1, "the ping on its topic, after the brokers said the topic is there");
        assertTrue(kafka.asked.get() >= 2, "the brokers were asked before the producer was handed the ping");
        assertEquals("", health.PingDetail());
    }

    @Test
    void under_pings_that_never_stop_the_topic_is_still_asked_for_again_when_its_minute_is_over() throws Exception {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> kafka.asked.get() == 1, "the topic asked for at start");
        java.util.concurrent.atomic.AtomicBoolean feeding = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread batches = new Thread(() -> { while (feeding.get()) ping.Publish("btcl", "cdr", 1); });   // the queue is never idle
        batches.start();
        try {
            kafka.topicIsThere = true;
            clock.addAndGet(SummaryChangeNotificationPublisher.AskAgainEveryMs);

            Eventually(() -> !kafka.taken.isEmpty(), "a ping on its topic: the brokers were asked again between two pings");
        } finally {
            feeding.set(false);
            batches.join(2_000);
        }
    }

    @Test
    void brokers_that_do_not_answer_are_said_as_that() {
        kafka.brokersAnswer = false;
        Started();

        Eventually(() -> warns.size() == 1, "the WARN");
        assertTrue(warns.get(0).contains("on " + Brokers + " — the brokers do not answer (TimeoutException: Timed out waiting"), warns.get(0));
        assertEquals(0, kafka.sends.get());
    }

    @Test
    void pings_do_not_pile_up_behind_a_topic_that_is_not_there() {
        kafka.topicIsThere = false;
        kafka.HoldEveryCall();                                   // the ping's thread is busy asking the brokers
        Started();

        long began = System.nanoTime();
        for (int batch = 0; batch < 2_000; batch++) ping.Publish("btcl", "cdr", 1);
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertTrue(tookMs < 1_000, "2,000 pings were handed over or dropped in " + tookMs + " ms");
        assertTrue(ping.Waiting() <= SummaryChangeNotificationPublisher.QueueCapacity, "the queue is bounded: " + ping.Waiting());
        assertEquals(1, warns.size(), "a ping that found the queue full is said — once a minute");
        assertTrue(warns.get(0).contains(Topic) && warns.get(0).contains(Brokers), warns.get(0));
    }

    // ── the health road: UP, with the ping as a detail ───────────────────────────────────────────────────────

    @Test
    void the_health_road_stays_up_and_carries_the_ping_as_a_detail() {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> !health.PingDetail().isEmpty(), "the detail");

        HealthCheckResponse answer = new CdrIngestHealthCheck(health).call();

        assertEquals(HealthCheckResponse.Status.UP, answer.getStatus(), "the rows are written; the summary service polls");
        String detail = String.valueOf(answer.getData().orElseThrow().get(CdrIngestHealthCheck.PingDetailKey));
        assertTrue(detail.contains("topic '" + Topic + "' on " + Brokers), detail);
    }

    @Test
    void a_healthy_ping_adds_nothing_to_the_health_road_and_an_ingest_that_is_down_keeps_its_reason() {
        assertTrue(new CdrIngestHealthCheck(health).call().getData().isEmpty(), "nothing to say: no data at all");

        health.Down("the dead-letter topic 'cdr_dlq_btcl' does not exist");
        health.PingNotPublished("the summary ping is not published");
        HealthCheckResponse answer = new CdrIngestHealthCheck(health).call();

        assertEquals(HealthCheckResponse.Status.DOWN, answer.getStatus());
        assertEquals("the dead-letter topic 'cdr_dlq_btcl' does not exist", answer.getData().orElseThrow().get("reason"));
        assertEquals("the summary ping is not published", answer.getData().orElseThrow().get(CdrIngestHealthCheck.PingDetailKey));
    }

    @Test
    void when_the_topic_is_there_again_the_detail_goes_without_waiting_for_a_batch_and_pings_arrive() {
        kafka.topicIsThere = false;
        Started();
        Eventually(() -> !health.PingDetail().isEmpty(), "the detail");

        kafka.topicIsThere = true;
        clock.addAndGet(SummaryChangeNotificationPublisher.AskAgainEveryMs);

        Eventually(() -> health.PingDetail().isEmpty(), "the detail gone: the topic was asked again and is there");
        ping.Publish("btcl", "cdr", 1);
        Eventually(() -> kafka.taken.size() == 1, "the next ping on its topic");
    }

    // ── switched off ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_ping_that_is_switched_off_or_names_no_broker_starts_nothing_and_publishes_nothing() {
        SummaryOutboxOptions off = new SummaryOutboxOptions();                    // Enabled = false
        SummaryOutboxOptions noBroker = new SummaryOutboxOptions();
        noBroker.Enabled = true;                                                  // … and no bootstrap-servers

        for (SummaryOutboxOptions options : List.of(off, noBroker)) {
            SummaryChangeNotificationPublisher silent = new SummaryChangeNotificationPublisher(options, health);

            assertFalse(silent.IsOn());
            assertFalse(silent.HasItsOwnThread(), "no thread for a ping that is switched off");
            silent.Publish("btcl", "cdr", 1);
            assertEquals(0, silent.Waiting());
        }
        assertEquals("", health.PingDetail());
    }
}
