package com.telcobright.billing.ingest;

import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule of the ingest loop (architect's ruling 2026-10-04): <b>an offset is never committed past a record
 * that is neither written nor dead-lettered.</b> Rows first, then the dead letters (acknowledged), then the
 * offsets; a publish that fails HOLDS the batch — its rows are not written again, nothing else is consumed, the
 * health road goes red after a setting's worth of tries; and the ingest is refused at start while the dead-letter
 * topic does not exist. Driven turn by turn ({@code Step()}) on Kafka's own mock consumer and producer.
 */
class CdrKafkaConsumerTests {
    private static final Logger LOG = Logger.getLogger(CdrKafkaConsumerTests.class);
    private static final String Topic = "cdr_btcl";
    private static final String DeadLetterTopic = "cdr_dlq_btcl";
    private static final TopicPartition P0 = new TopicPartition(Topic, 0);

    private static final String NOT_JSON = "{ this is not json";

    /** Kafka's mock consumer, plus two things a test must see: the rebalance listener the loop subscribed with,
     * and how many dead letters were on their topic at the moment an offset was committed. */
    private static final class RecordingConsumer extends MockConsumer<String, String> {
        ConsumerRebalanceListener listener;
        final List<Integer> deadLettersOnTheTopicAtEachCommit = new ArrayList<>();
        MockProducer<String, String> deadLetterTopic;

        RecordingConsumer() { super("earliest"); }

        @Override
        public synchronized void subscribe(Collection<String> topics, ConsumerRebalanceListener l) {
            listener = l;
            super.subscribe(topics, l);
        }

        /** Every commit of the mock ends here: its no-argument {@code commitSync()} calls this one. */
        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            deadLettersOnTheTopicAtEachCommit.add(deadLetterTopic.history().size());
            super.commitSync(offsets);
        }
    }

    /** One ingest loop on mocks: the tenant {@code btcl} is loaded, partition 0 of the cdr topic is assigned. */
    private static final class Ingest {
        final RecordingConsumer kafka = new RecordingConsumer();
        final MockProducer<String, String> deadLetterTopic = DeadLetterPublisherTests.producer(true);
        final IngestHealth health = new IngestHealth();
        final List<MultiTenantCdrBatch> written = new ArrayList<>();      // the batches that had rows to write
        final List<Long> waits = new ArrayList<>();
        final CdrIngestOptions opts = new CdrIngestOptions();
        boolean tenantConfigLoaded = true;
        RuntimeException rowsFail;
        long nextOffset;
        CdrKafkaConsumer loop;

        Ingest() { this(DeadLetterTopic); }

        Ingest(String deadLetterTopicName) {
            opts.Enabled = true;
            opts.BootstrapServers = "broker:9092";
            opts.Topic = Topic;
            opts.DeadLetterTopic = deadLetterTopicName;
            kafka.deadLetterTopic = deadLetterTopic;
            ITenantRegistry loaded = RatifiedWireTests.registryWith("btcl");
            ITenantRegistry registry = new ITenantRegistry() {
                @Override public boolean IsLoaded() { return tenantConfigLoaded; }
                @Override public Tenant FindByDbName(String dbName) { return loaded.FindByDbName(dbName); }
                @Override public List<Tenant> AncestorChain(String dbName) { return List.of(); }
                @Override public Collection<Tenant> Roots() { return loaded.Roots(); }
            };
            DeadLetterPublisher publisher = deadLetterTopicName.isBlank()
                    ? null : new DeadLetterPublisher(deadLetterTopic, deadLetterTopicName, LOG);
            loop = new CdrKafkaConsumer(kafka, new CdrEventPreprocessor(registry), this::writeRows, publisher,
                    registry, opts, health, waits::add, LOG);
            kafka.rebalance(List.of(P0));
            kafka.updateBeginningOffsets(Map.of(P0, 0L));
        }

        private void writeRows(MultiTenantCdrBatch batch) {
            assertEquals(0, deadLetterTopic.history().size() - deadLettersBeforeThisBatch,
                    "the rows are written BEFORE the batch's dead letters are published");
            if (rowsFail != null) throw rowsFail;
            if (!batch.tenants().isEmpty()) written.add(batch);     // a batch of dead letters only writes no row
        }

        int deadLettersBeforeThisBatch;

        Ingest theDeadLetterTopicExists() {
            kafka.updatePartitions(DeadLetterTopic, List.of(new PartitionInfo(DeadLetterTopic, 0, null, null, null)));
            return this;
        }

        Ingest arrives(String value) {
            kafka.addRecord(new ConsumerRecord<>(Topic, 0, nextOffset++, "key", value));
            return this;
        }

        void turn() {
            deadLettersBeforeThisBatch = deadLetterTopic.history().size();
            loop.Step();
        }

        Long committed() {
            OffsetAndMetadata o = kafka.committed(Set.of(P0)).get(P0);
            return o == null ? null : o.offset();
        }
    }

    // ── rows, then dead letters, then offsets ────────────────────────────────────────────────────────────────

    @Test
    void the_rows_are_written_then_the_dead_letter_is_published_and_only_then_the_offsets_move() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists()
                .arrives(RatifiedWireTests.A_REFUSED_VIEW).arrives(NOT_JSON);

        ingest.turn();

        assertEquals(1, ingest.written.size());
        assertEquals(List.of("btcl"), ingest.written.get(0).tenants().stream().map(PerTenantCdrs::tenant).toList());
        assertEquals(1, ingest.deadLetterTopic.history().size());
        assertEquals(DeadLetterTopic, ingest.deadLetterTopic.history().get(0).topic());
        assertTrue(ingest.deadLetterTopic.history().get(0).value().contains("decode failed"));
        assertEquals(2L, ingest.committed(), "both records are behind the committed offset");
        assertEquals(List.of(1), ingest.kafka.deadLettersOnTheTopicAtEachCommit,
                "at the commit the dead letter was already on its topic");
        assertTrue(ingest.health.Current().Up());
    }

    @Test
    void a_tier_that_does_not_commit_publishes_nothing_commits_nothing_and_rewinds() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists()
                .arrives(RatifiedWireTests.A_REFUSED_VIEW).arrives(NOT_JSON);
        ingest.rowsFail = new IllegalStateException("tenant 'btcl' batch not committed: connection refused");

        ingest.turn();

        assertEquals(0, ingest.deadLetterTopic.history().size(), "a rewound batch must not put its dead letters on the topic");
        assertNull(ingest.committed());
        assertEquals(0L, ingest.kafka.position(P0), "rewound to the batch's first record");
        assertEquals(List.of(CdrKafkaConsumer.ErrorBackoffSeconds * 1000L), ingest.waits);
    }

    // ── a publish that fails: the batch is held ──────────────────────────────────────────────────────────────

    @Test
    void a_dead_letter_that_cannot_be_published_holds_the_batch_and_no_offset_moves() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists()
                .arrives(RatifiedWireTests.A_REFUSED_VIEW).arrives(NOT_JSON);
        ingest.deadLetterTopic.sendException = new org.apache.kafka.common.errors.TimeoutException("no leader for cdr_dlq_btcl-0");

        ingest.turn();                                    // rows written; the publish fails
        ingest.arrives(RatifiedWireTests.A_REFUSED_VIEW.replace("r001", "r002"));   // traffic goes on arriving
        ingest.turn();                                    // second try
        ingest.turn();                                    // third try

        assertNull(ingest.committed(), "no offset is committed past a record that is not dead-lettered");
        assertEquals(1, ingest.written.size(), "the held batch's rows are written ONCE; the later record is not consumed");
        assertEquals(Set.of(P0), ingest.kafka.paused());
        assertFalse(ingest.health.Current().Up(), "red after " + ingest.opts.DeadLetterUnhealthyAfterTries + " tries");
        assertTrue(ingest.health.Current().Reason().contains("'" + DeadLetterTopic + "'"), ingest.health.Current().Reason());
        assertTrue(ingest.health.Current().Reason().contains("3 tries"), ingest.health.Current().Reason());
    }

    @Test
    void the_health_road_stays_green_until_the_settings_worth_of_tries() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists().arrives(NOT_JSON);
        ingest.opts.DeadLetterUnhealthyAfterTries = 4;
        ingest.deadLetterTopic.sendException = new org.apache.kafka.common.errors.TimeoutException("down");

        ingest.turn(); ingest.turn(); ingest.turn();      // tries 1, 2, 3
        assertTrue(ingest.health.Current().Up(), "three tries are fewer than the setting's four");

        ingest.turn();                                    // try 4
        assertFalse(ingest.health.Current().Up());
    }

    @Test
    void when_the_topic_answers_again_the_held_offsets_are_committed_and_the_ingest_goes_on() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists()
                .arrives(RatifiedWireTests.A_REFUSED_VIEW).arrives(NOT_JSON);
        ingest.deadLetterTopic.sendException = new org.apache.kafka.common.errors.TimeoutException("down");
        ingest.turn(); ingest.turn(); ingest.turn();      // held, health red
        ingest.arrives(RatifiedWireTests.A_REFUSED_VIEW.replace("r001", "r002"));

        ingest.deadLetterTopic.sendException = null;      // the topic is back
        ingest.turn();                                    // the publish goes through

        assertEquals(1, ingest.deadLetterTopic.history().size());
        assertEquals(2L, ingest.committed(), "exactly the held batch: not the record that arrived meanwhile");
        assertTrue(ingest.kafka.paused().isEmpty());
        assertTrue(ingest.health.Current().Up());
        assertEquals(1, ingest.written.size());

        ingest.turn();                                    // the next poll takes the waiting record
        assertEquals(2, ingest.written.size());
        assertEquals(3L, ingest.committed());
    }

    @Test
    void a_held_batch_whose_partition_is_taken_away_is_dropped_and_nothing_stays_paused() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists().arrives(NOT_JSON);
        ingest.deadLetterTopic.sendException = new org.apache.kafka.common.errors.TimeoutException("down");
        ingest.turn(); ingest.turn(); ingest.turn();      // held, paused, red
        assertFalse(ingest.health.Current().Up());

        ingest.kafka.listener.onPartitionsRevoked(List.of(P0));       // its new owner reads the batch again
        ingest.deadLetterTopic.sendException = null;
        ingest.arrives(RatifiedWireTests.A_REFUSED_VIEW);
        ingest.turn();

        assertTrue(ingest.health.Current().Up());
        assertTrue(ingest.kafka.paused().isEmpty(), "a hold that was dropped must not leave a partition paused");
        assertEquals(1, ingest.written.size(), "the loop consumes again");
        assertEquals(0, ingest.deadLetterTopic.history().size(), "the dropped batch's dead letters are its new owner's to publish");
    }

    // ── refused at start ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_ingest_consumes_nothing_while_the_dead_letter_topic_does_not_exist_and_starts_when_it_does() {
        Ingest ingest = new Ingest().arrives(RatifiedWireTests.A_REFUSED_VIEW);     // no dead-letter topic on the brokers

        ingest.turn();
        ingest.turn();

        assertTrue(ingest.written.isEmpty(), "refused: nothing is consumed");
        assertNull(ingest.committed());
        assertFalse(ingest.health.Current().Up());
        assertTrue(ingest.health.Current().Reason().contains("the dead-letter topic '" + DeadLetterTopic + "' does not exist on broker:9092"),
                ingest.health.Current().Reason());
        assertEquals(List.of(5000L, 5000L), ingest.waits, "it looks again after a back-off, it does not spin");

        ingest.theDeadLetterTopicExists();                // the window creates it; no restart
        ingest.turn();

        assertEquals(1, ingest.written.size());
        assertEquals(1L, ingest.committed());
        assertTrue(ingest.health.Current().Up());
    }

    @Test
    void a_profile_that_names_no_dead_letter_topic_is_refused_in_words() {
        Ingest ingest = new Ingest("").arrives(RatifiedWireTests.A_REFUSED_VIEW);

        ingest.turn();

        assertTrue(ingest.written.isEmpty());
        assertFalse(ingest.health.Current().Up());
        assertTrue(ingest.health.Current().Reason().contains("billing.cdr-ingest.dead-letter-topic is empty"),
                ingest.health.Current().Reason());
    }

    @Test
    void nothing_is_polled_before_the_tenant_config_is_loaded() {
        Ingest ingest = new Ingest().theDeadLetterTopicExists().arrives(RatifiedWireTests.A_REFUSED_VIEW);
        ingest.tenantConfigLoaded = false;

        ingest.turn();
        assertTrue(ingest.written.isEmpty());
        assertNull(ingest.committed());

        ingest.tenantConfigLoaded = true;
        ingest.turn();
        assertEquals(1, ingest.written.size());
    }

    // ── a new consumer group starts at 'earliest' (a profile value) ──────────────────────────────────────────

    @Test
    void the_consumer_starts_a_new_group_where_the_profile_says_and_commits_by_hand() {
        CdrIngestOptions opts = new CdrIngestOptions();
        opts.BootstrapServers = "broker:9092";
        opts.ConsumerGroup = "billing-core-cdr-ingest";

        Properties byDefault = CdrKafkaConsumer.ConsumerProperties(opts);
        opts.AutoOffsetReset = "latest";
        Properties aTenantsChoice = CdrKafkaConsumer.ConsumerProperties(opts);

        assertEquals("earliest", byDefault.get("auto.offset.reset"));
        assertEquals("latest", aTenantsChoice.get("auto.offset.reset"));
        assertEquals("false", byDefault.get("enable.auto.commit"));
        assertEquals("billing-core-cdr-ingest", byDefault.get("group.id"));
    }
}
