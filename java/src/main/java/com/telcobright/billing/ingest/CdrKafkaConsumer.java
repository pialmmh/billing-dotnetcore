package com.telcobright.billing.ingest;

import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The inbound Kafka CDR ingest loop (T1) — the real "cdr ingest" path. It subscribes to the cdr topic of the
 * ratified wire (the deployed name is {@code cdr_<root tenant>}; key = channelCallUuid, value = {@code CdrEvent[]}
 * for one call), and per poll-batch: run {@link CdrEventPreprocessor} (decode → validate → map → group by tenant →
 * attach registry context), then write each tenant group through {@link CdrProcessor#ProcessBatch}.
 *
 * <p><b>The one rule of this loop (architect's ruling 2026-10-04): an offset is never committed past a record that
 * is neither written nor dead-lettered.</b> The order of a poll-batch is fixed:
 * <ol>
 *   <li>every tier's rows are committed in its schema (a tier that does not commit = nothing below happens: the
 *       batch is rewound and read again; the tiers that did commit are skipped by the idempotency);</li>
 *   <li>the records the preprocessor refused are published to the dead-letter topic, and the brokers acknowledge
 *       each one ({@code acks=all});</li>
 *   <li>only then the batch's offsets are committed.</li>
 * </ol>
 * When step 2 fails the batch is HELD: its rows stay committed, its dead letters are kept, the partitions are
 * paused, and the publish is tried again with a back-off — one ERROR line per try, naming the topic. After
 * {@code billing.cdr-ingest.dead-letter-unhealthy-after-tries} tries the health road goes red
 * ({@link IngestHealth}). Nothing is consumed while a batch is held: that is the point.
 *
 * <p><i>Why the held batch is not simply read again at every try:</i> the redelivery would write the batch's rows
 * again wherever the idempotency does not reach — on MySQL that is {@code cdrerror} — once per try. The rows are
 * written once; only the publish is repeated. A restart during a hold does read the batch again, as any restart
 * between the rows and the offsets always did.
 *
 * <p><b>Refused at start.</b> The ingest consumes nothing until the dead-letter topic exists (and the profile names
 * one): a refused record would have nowhere to go. It says so — an ERROR line with the topic and the brokers, and a
 * red health road — and looks again every few seconds, so a window that creates the topic needs no restart.
 *
 * <p><b>Scope:</b> {@code CdrProcessor.ProcessBatch} opens ONE connection + ONE transaction <b>per tenant</b>, so a
 * poll-batch spanning tiers commits tier by tier, not as one cross-schema transaction; the idempotency on the
 * tenant's schema makes the redelivery of a half-written batch safe.
 */
public final class CdrKafkaConsumer {
    static final int ErrorBackoffSeconds = 5;
    private static final int MaxDeadLetterLogChars = 500;
    private static final int DrainTimeoutSeconds = 20;   // cutover drain budget for the in-flight poll-batch
    private static final int TopicLookupSeconds = 10;
    private static final int RefusalLogEveryTurns = 12;  // the refusal's ERROR line: at once, then about once a minute

    /** Writes every tier's rows of one preprocessed poll-batch; throws when a tenant's slice did not commit. */
    @FunctionalInterface
    interface RowWriter {
        void Write(MultiTenantCdrBatch batch);
    }

    /** The loop's waits (a back-off, the pause before the next look): real sleeps in a deployment, none in a test. */
    @FunctionalInterface
    interface Waiter {
        void Wait(long ms);
    }

    /** A poll-batch whose rows are committed and whose dead letters are not on their topic yet. */
    private static final class HeldBatch {
        final Map<TopicPartition, OffsetAndMetadata> Offsets;
        final List<DeadLetteredCdr> Refused;
        int Tries;

        HeldBatch(Map<TopicPartition, OffsetAndMetadata> offsets, List<DeadLetteredCdr> refused) {
            Offsets = offsets;
            Refused = refused;
        }
    }

    private final Consumer<String, String> consumer;
    private final CdrEventPreprocessor preprocessor;
    private final RowWriter rowWriter;
    private final DeadLetterPublisher deadLetters;   // null only when the profile names no dead-letter topic (the ingest is then refused)
    private final ITenantRegistry registry;
    private final CdrIngestOptions opts;
    private final IngestHealth health;
    private final Waiter waiter;
    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cdr-ingest-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private boolean waitedForConfig;
    private boolean warnedAboutConsume;      // log a recurring consume error ONCE, not on every poll
    private boolean deadLetterTopicSeen;     // the ingest is refused until it is
    private int refusals;
    private HeldBatch held;

    CdrKafkaConsumer(Consumer<String, String> consumer, CdrEventPreprocessor preprocessor, RowWriter rowWriter,
            DeadLetterPublisher deadLetters, ITenantRegistry registry, CdrIngestOptions opts, IngestHealth health,
            Waiter waiter, Logger log) {
        this.consumer = consumer;
        this.preprocessor = preprocessor;
        this.rowWriter = rowWriter;
        this.deadLetters = deadLetters;
        this.registry = registry;
        this.opts = opts;
        this.health = health;
        this.waiter = waiter;
        this.log = log;
        consumer.subscribe(List.of(opts.Topic), new DropTheHeldBatchWhenItsPartitionGoes());
    }

    /** Build the consumer, subscribe to the CDR topic, and launch the poll loop. Returns {@code null} when the
     * ingest is disabled or no broker is configured (the caller then relies on the gRPC debug entry). */
    public static CdrKafkaConsumer Start(CdrProcessor processor, ITenantRegistry registry,
            CdrIngestOptions opts, int switchId, IngestHealth health, Logger log) {
        if (!opts.Enabled) {
            log.info("cdr ingest disabled (billing.cdr-ingest.enabled=false) — cdrs arrive via gRPC only");
            return null;
        }
        if (opts.BootstrapServers == null || opts.BootstrapServers.isBlank()) {
            log.warn("cdr ingest enabled but billing.cdr-ingest.bootstrap-servers is empty — NOT starting");
            return null;
        }

        MultiTenantCdrProcessor writer = new MultiTenantCdrProcessor(processor, log);
        CdrKafkaConsumer loop = new CdrKafkaConsumer(new KafkaConsumer<>(ConsumerProperties(opts)),
                new CdrEventPreprocessor(registry, switchId), writer::Process, DeadLetterPublisherOf(opts, log),
                registry, opts, health, CdrKafkaConsumer::Sleep, log);
        loop.exec.submit(loop::run);
        log.infof("cdr ingest listening on topic '%s' (servers=%s, group=%s, a new group starts at %s, dead letters -> '%s')",
                opts.Topic, opts.BootstrapServers, opts.ConsumerGroup, opts.AutoOffsetReset, opts.DeadLetterTopic);
        return loop;
    }

    /** The consumer's properties. A group with no committed offset starts where the profile says: {@code earliest}
     * by default (ratified — nothing published before the group's first start may be skipped); it has no effect
     * once the group has offsets. Offsets are committed by hand, after the rows and the dead letters. */
    static Properties ConsumerProperties(CdrIngestOptions opts) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, opts.ConsumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, opts.AutoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private static DeadLetterPublisher DeadLetterPublisherOf(CdrIngestOptions opts, Logger log) {
        boolean named = opts.DeadLetterTopic != null && !opts.DeadLetterTopic.isBlank();
        return named ? DeadLetterPublisher.ForTopic(opts.BootstrapServers, opts.DeadLetterTopic, log) : null;
    }

    private void run() {
        try {
            while (running) Step();
        } catch (WakeupException shutdown) {
            // normal: stop() woke the consumer out of a poll
        } catch (Exception fatal) {
            log.error("cdr ingest loop stopped on an unexpected error", fatal);
            health.Down("the cdr ingest loop stopped on an unexpected error: " + fatal);
        } finally {
            try { consumer.close(); } catch (Exception ignore) { /* best effort */ }
            if (deadLetters != null) deadLetters.close();
        }
    }

    /** One turn of the ingest loop. It reads in the order of the guarantees. */
    void Step() {
        if (!TenantConfigIsLoaded()) return;
        if (!DeadLetterTopicIsThere()) return;
        if (held != null) { RetryTheHeldDeadLetters(); return; }

        ResumeWhatAHoldLeftPaused();
        ConsumerRecords<String, String> records = Poll();
        if (records == null || records.isEmpty()) return;
        WriteRowsThenDeadLettersThenOffsets(records);
    }

    /** No batch is held, so nothing may stay paused: a hold that a rebalance dropped (its partition went away)
     * leaves the consumer's OTHER partitions paused, and they would never be read again. */
    private void ResumeWhatAHoldLeftPaused() {
        if (!consumer.paused().isEmpty()) consumer.resume(consumer.paused());
    }

    // ── the gates before a poll ──────────────────────────────────────────────────────────────────────────────

    /** HOLD OFF until the tenant registry has its first config load. Quarkus starts this bean and the
     * TenantHierarchyLoader without a guaranteed order; polling before the registry is loaded dead-letters every
     * record as "unknown tenant" (observed live 2026-07-16). Waiting costs nothing: the records stay in the topic. */
    private boolean TenantConfigIsLoaded() {
        if (registry.IsLoaded()) return true;
        if (!waitedForConfig) {
            log.info("cdr ingest waiting for the first tenant-config load before consuming");
            waitedForConfig = true;
        }
        waiter.Wait(500);
        return false;
    }

    /** The refusal at start: nothing is consumed until the dead-letter topic exists. Looked at again each turn. */
    private boolean DeadLetterTopicIsThere() {
        if (deadLetterTopicSeen) return true;
        String missing = WhyTheDeadLetterTopicIsMissing();
        if (missing == null) return AcceptTheIngest();
        RefuseTheIngest(missing);
        return false;
    }

    /** {@code null} when the topic is there; else the refusal, in words. A lookup never creates the topic. */
    private String WhyTheDeadLetterTopicIsMissing() {
        String topic = opts.DeadLetterTopic;
        if (topic == null || topic.isBlank())
            return "billing.cdr-ingest.dead-letter-topic is empty: a record this service refuses would have nowhere to go";
        try {
            if (consumer.listTopics(Duration.ofSeconds(TopicLookupSeconds)).containsKey(topic)) return null;
            return "the dead-letter topic '" + topic + "' does not exist on " + opts.BootstrapServers
                    + " — create it (a deployment's window does); the ingest starts by itself once it is there";
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (Exception ex) {
            return "the dead-letter topic '" + topic + "' could not be looked up on " + opts.BootstrapServers
                    + " (" + ex.getMessage() + ")";
        }
    }

    private boolean AcceptTheIngest() {
        deadLetterTopicSeen = true;
        refusals = 0;
        health.Up();
        log.infof("cdr ingest: the dead-letter topic '%s' is there — consuming '%s'", opts.DeadLetterTopic, opts.Topic);
        return true;
    }

    private void RefuseTheIngest(String reason) {
        if (refusals++ % RefusalLogEveryTurns == 0)
            log.errorf("cdr ingest REFUSED — nothing is consumed from '%s': %s", opts.Topic, reason);
        health.Down("cdr ingest refused: " + reason);
        waiter.Wait(ErrorBackoffSeconds * 1000L);
    }

    // ── a poll-batch: rows, then dead letters, then offsets ──────────────────────────────────────────────────

    /** {@code null} on a consume error (logged once, then a back-off); a shutdown's wakeup is let through. */
    private ConsumerRecords<String, String> Poll() {
        try {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(opts.PollMs));
            warnedAboutConsume = false;
            return records;
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (Exception ex) {
            if (!warnedAboutConsume) {
                log.warnf("cdr consume error (%s); retrying every %ds until it clears", ex.getMessage(), ErrorBackoffSeconds);
                warnedAboutConsume = true;
            }
            waiter.Wait(ErrorBackoffSeconds * 1000L);
            return null;
        }
    }

    private void WriteRowsThenDeadLettersThenOffsets(ConsumerRecords<String, String> records) {
        try {
            List<DeadLetteredCdr> refused = WriteRows(records);
            if (PublishOrHold(records, refused)) consumer.commitSync();
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (Exception ex) {
            // do NOT commit; rewind to the batch start so it is redelivered (at-least-once).
            log.error("cdr poll-batch failed; rewinding for redelivery", ex);
            SeekBackToBatchStart(records);
            waiter.Wait(ErrorBackoffSeconds * 1000L);
        }
    }

    /** Preprocess the poll-batch and write each tenant group to its own schema. Throws if any tenant slice did
     * not commit — then nothing is published and no offset moves; the tenants that did commit are skipped by the
     * idempotency when the batch is read again. Returns the records the preprocessor refused. */
    private List<DeadLetteredCdr> WriteRows(ConsumerRecords<String, String> records) {
        MultiTenantCdrBatch batch = preprocessor.Preprocess(ValuesOf(records));
        for (DeadLetteredCdr d : batch.deadLetters())
            log.warnf("cdr dead-letter [%s]: %s", d.reason(), Truncate(d.payload()));
        rowWriter.Write(batch);
        return batch.deadLetters();
    }

    private static List<String> ValuesOf(ConsumerRecords<String, String> records) {
        List<String> values = new ArrayList<>();
        for (ConsumerRecord<String, String> rec : records)
            if (rec.value() != null) values.add(rec.value());
        return values;
    }

    /** {@code true}: every refused record is on the dead-letter topic — the offsets may be committed. {@code false}:
     * the batch is held. Published only now, after the rows: a batch that is rewound because a tier did not commit
     * must not put its dead letters on the topic once per attempt. */
    private boolean PublishOrHold(ConsumerRecords<String, String> records, List<DeadLetteredCdr> refused) {
        if (refused.isEmpty() || deadLetters.Publish(refused)) return true;
        held = new HeldBatch(OffsetsAfter(records), refused);
        consumer.pause(consumer.assignment());
        CountAHeldTry();
        return false;
    }

    private static Map<TopicPartition, OffsetAndMetadata> OffsetsAfter(ConsumerRecords<String, String> records) {
        Map<TopicPartition, OffsetAndMetadata> next = new HashMap<>();
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, String>> recs = records.records(tp);
            if (!recs.isEmpty()) next.put(tp, new OffsetAndMetadata(recs.get(recs.size() - 1).offset() + 1));
        }
        return next;
    }

    // ── a held batch ─────────────────────────────────────────────────────────────────────────────────────────

    private void RetryTheHeldDeadLetters() {
        StayInTheGroupWhilePaused();
        if (held == null) return;                    // a rebalance took the batch's partition: it is read again
        if (!deadLetters.Publish(held.Refused)) { CountAHeldTry(); return; }
        CommitTheHeldOffsets();
    }

    /** The back-off of a held batch IS this poll: every partition is paused, so it fetches nothing and waits its
     * timeout — and keeps this consumer a member of its group. A partition assigned INSIDE the poll is not paused
     * yet; what it delivers is put back. */
    private void StayInTheGroupWhilePaused() {
        consumer.pause(consumer.assignment());
        ConsumerRecords<String, String> stray = consumer.poll(Duration.ofMillis(ErrorBackoffSeconds * 1000L));
        if (!stray.isEmpty()) SeekBackToBatchStart(stray);
    }

    private void CountAHeldTry() {
        held.Tries++;
        log.errorf("cdr dead letters NOT on topic '%s' (try %d): %d record(s) wait and the batch's offsets are held;"
                + " next try in %ds", deadLetters.Topic(), held.Tries, held.Refused.size(), ErrorBackoffSeconds);
        if (held.Tries >= opts.DeadLetterUnhealthyAfterTries)
            health.Down("the dead letters of a cdr batch cannot be published to topic '" + deadLetters.Topic() + "' ("
                    + held.Tries + " tries): its offsets are held and nothing is consumed");
    }

    private void CommitTheHeldOffsets() {
        try {
            consumer.commitSync(held.Offsets);
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (Exception ex) {
            // The rows are written and the dead letters published, so this is not a loss: the next commit covers
            // these offsets, and a restart before it reads the batch again (the idempotency skips its rows).
            log.warnf("cdr ingest: the held batch's offsets were not committed (%s); the next commit covers them", ex.getMessage());
        }
        log.infof("cdr ingest: %d dead letter(s) are on topic '%s' after %d failed tries — consuming again",
                held.Refused.size(), deadLetters.Topic(), held.Tries);
        held = null;
        consumer.resume(consumer.paused());
        health.Up();
    }

    /** A held batch whose partition leaves this consumer is dropped: its new owner reads it again from the
     * committed offset (the rows are skipped by the idempotency, the dead letters are published by the owner). */
    private final class DropTheHeldBatchWhenItsPartitionGoes implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (held == null || partitions.stream().noneMatch(held.Offsets::containsKey)) return;
            log.warnf("cdr ingest: the held batch's partition was taken away — it is read again from the committed offset");
            held = null;
            health.Up();
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            // nothing: a new assignment starts from its committed offset
        }
    }

    // ── small things ─────────────────────────────────────────────────────────────────────────────────────────

    private void SeekBackToBatchStart(ConsumerRecords<String, String> records) {
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, String>> recs = records.records(tp);
            if (!recs.isEmpty()) {
                try { consumer.seek(tp, recs.get(0).offset()); } catch (Exception ignore) { /* rebalanced away */ }
            }
        }
    }

    private static String Truncate(String s) {
        if (s == null) return "";
        return s.length() <= MaxDeadLetterLogChars ? s : s.substring(0, MaxDeadLetterLogChars) + "…";
    }

    private static void Sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public void stop() {
        // GRACEFUL DRAIN (cutover-safe): stop taking NEW poll-batches (running=false; the loop exits within one
        // PollMs once idle), then let the in-flight batch finish — write cdr + cdrerror + acc_chargeable +
        // summary_affected (tx commit = flush), publish its dead letters and commitSync() the offsets — BEFORE we
        // exit. Only if the drain overruns do we wakeup + interrupt (at-least-once redelivery covers that).
        running = false;
        boolean drained = GracefulDrain.drain(exec, DrainTimeoutSeconds, TimeUnit.SECONDS,
                () -> { try { consumer.wakeup(); } catch (Exception ignore) { /* best effort */ } });
        if (drained) log.info("cdr ingest drained cleanly (in-flight batch written + offsets committed)");
        else log.warnf("cdr ingest drain overran %ds; forced (in-flight batch redelivered on restart)", DrainTimeoutSeconds);
    }
}
