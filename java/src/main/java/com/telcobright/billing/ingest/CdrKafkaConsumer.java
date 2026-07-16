package com.telcobright.billing.ingest;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The inbound Kafka CDR ingest loop (T1) — the real "cdr ingest" path that replaces the {@link CdrProcessor}
 * startup stub. It subscribes to the rated-CDR topic (default {@code cdr}; key = channelCallUuid, value =
 * {@code CdrEvent[]} for one call), and per poll-batch: run {@link CdrEventPreprocessor} (decode → validate →
 * map → group by tenant → attach registry context), then write each tenant group via the existing
 * {@link CdrProcessor#ProcessBatch}. Kafka offsets are committed <b>only after</b> the DB writes commit; on any
 * failure it seeks the batch back and retries (at-least-once). Bad records are dead-lettered (logged; not
 * poisoning the batch). Mirrors the config-event consume loop
 * ({@code tenantconfigsync.internal.ConfigEventConsumerLoop}).
 *
 * <p><b>FIRST-CUT SCOPE (flagged):</b> this reuses {@code CdrProcessor.ProcessBatch}, which opens ONE
 * connection + ONE transaction <b>per tenant</b>. So a poll-batch spanning tiers commits per-tenant, not as a
 * single cross-schema transaction. The contract's {@code MultiTenantCdrProcessor} (ONE tx across ALL schemas,
 * §4) is the follow-up; cross-batch dedup (T3) is not here yet, so a redelivery can double-write until the
 * idempotency key lands.
 */
public final class CdrKafkaConsumer {
    private static final int ErrorBackoffSeconds = 5;
    private static final int MaxDeadLetterLogChars = 500;

    private final Consumer<String, String> consumer;
    private final CdrEventPreprocessor preprocessor;
    private final CdrProcessor processor;
    private final ITenantRegistry registry;
    private final CdrIngestOptions opts;
    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cdr-ingest-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private CdrKafkaConsumer(Consumer<String, String> consumer, CdrEventPreprocessor preprocessor,
            CdrProcessor processor, ITenantRegistry registry, CdrIngestOptions opts, Logger log) {
        this.consumer = consumer;
        this.preprocessor = preprocessor;
        this.processor = processor;
        this.registry = registry;
        this.opts = opts;
        this.log = log;
    }

    /** Build the consumer, subscribe to the CDR topic, and launch the poll loop. Returns {@code null} when the
     * ingest is disabled or no broker is configured (the caller then relies on the gRPC debug entry). */
    public static CdrKafkaConsumer Start(CdrProcessor processor, ITenantRegistry registry,
            CdrIngestOptions opts, Logger log) {
        if (!opts.Enabled) {
            log.info("cdr ingest disabled (billing.cdr-ingest.enabled=false) — cdrs arrive via gRPC only");
            return null;
        }
        if (opts.BootstrapServers == null || opts.BootstrapServers.isBlank()) {
            log.warn("cdr ingest enabled but billing.cdr-ingest.bootstrap-servers is empty — NOT starting");
            return null;
        }

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, opts.ConsumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");   // only NEW cdrs, not the topic's history
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // commit MANUALLY, after the DB commit
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(opts.Topic));

        CdrKafkaConsumer loop = new CdrKafkaConsumer(consumer, new CdrEventPreprocessor(registry), processor, registry, opts, log);
        loop.exec.submit(loop::run);
        log.infof("cdr ingest listening on topic '%s' (servers=%s, group=%s)",
                opts.Topic, opts.BootstrapServers, opts.ConsumerGroup);
        return loop;
    }

    private void run() {
        boolean warned = false;   // log a recurring consume error ONCE, not on every poll
        boolean waitedForConfig = false;
        try {
            while (running) {
                // HOLD OFF until the tenant registry has its first config load. Quarkus starts this bean and
                // the TenantHierarchyLoader without a guaranteed order; polling before the registry is loaded
                // dead-letters every record as "unknown tenant" AND commits their offsets — silent data loss
                // (observed live 2026-07-16). Waiting costs nothing: the records stay in the topic.
                if (!registry.IsLoaded()) {
                    if (!waitedForConfig) {
                        log.info("cdr ingest waiting for the first tenant-config load before consuming");
                        waitedForConfig = true;
                    }
                    sleep(500);
                    continue;
                }

                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(opts.PollMs));
                    warned = false;
                } catch (WakeupException we) {
                    break;               // normal shutdown
                } catch (Exception ex) {
                    if (!warned) {
                        log.warnf("cdr consume error (%s); retrying every %ds until it clears",
                                ex.getMessage(), ErrorBackoffSeconds);
                        warned = true;
                    }
                    sleep(ErrorBackoffSeconds * 1000L);
                    continue;
                }
                if (records.isEmpty()) continue;

                try {
                    ProcessPollBatch(records);
                    consumer.commitSync();        // offsets AFTER the DB writes committed
                } catch (Exception ex) {
                    // do NOT commit; rewind to the batch start so it is redelivered (at-least-once).
                    log.error("cdr poll-batch failed; rewinding for redelivery", ex);
                    SeekBackToBatchStart(records);
                    sleep(ErrorBackoffSeconds * 1000L);
                }
            }
        } catch (Exception fatal) {
            log.error("cdr ingest loop stopped on an unexpected error", fatal);
        } finally {
            try { consumer.close(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    /** Preprocess the poll-batch and write each tenant group; throws if any tenant batch did not commit. */
    private void ProcessPollBatch(ConsumerRecords<String, String> records) {
        List<String> values = new ArrayList<>();
        for (ConsumerRecord<String, String> rec : records)
            if (rec.value() != null) values.add(rec.value());

        MultiTenantCdrBatch batch = preprocessor.Preprocess(values);

        for (DeadLetteredCdr d : batch.deadLetters())
            log.warnf("cdr dead-letter [%s]: %s", d.reason(), Truncate(d.payload()));
        // TODO(T1 follow-up): republish dead-letters to opts.DeadLetterTopic instead of only logging.

        for (PerTenantCdrs t : batch.tenants()) {
            CdrProcessingResult r = processor.ProcessBatch(t.tenant(), t.cdrs());
            if (!r.Committed())
                throw new IllegalStateException("tenant '" + t.tenant() + "' batch not committed: " + r.Error());
        }
    }

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

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public void stop() {
        running = false;
        try { consumer.wakeup(); } catch (Exception ignore) { /* best effort */ }
        exec.shutdownNow();
    }
}
