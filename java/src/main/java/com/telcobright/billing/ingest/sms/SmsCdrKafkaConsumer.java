package com.telcobright.billing.ingest.sms;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import com.telcobright.billing.ingest.GracefulDrain;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.dependencies.SmsOutgoingOptions;
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
import java.util.concurrent.TimeUnit;

/**
 * The OUTGOING-SMS Kafka intake: every record on the configured SMS topic is parsed ({@link SmsCdrEventParser})
 * and billed through {@link CdrProcessor#ProcessSmsOutgoingBatch} — the dedicated SMS pipeline, fixed SG20. It
 * never touches the voice consumer, topic or pipeline.
 *
 * <p>Same delivery contract as the voice consumer: offsets are committed ONLY after the tenant's DB transaction
 * committed; any failure rewinds the poll-batch for redelivery (at-least-once), and the batch's idCall dedup makes
 * a redelivery write nothing twice. Unparseable records are dead-lettered (logged) so one bad record cannot
 * poison the batch. Consumption waits for the first tenant-config load.</p>
 *
 * <p>FAIL-SAFE start: returns {@code null} (consumer not started) unless {@link SmsOutgoingOptions} is fully
 * configured — enabled, tenant, topic, group and brokers. No topic or group name is defaulted.</p>
 */
public final class SmsCdrKafkaConsumer {
    private static final int ErrorBackoffSeconds = 5;
    private static final int MaxDeadLetterLogChars = 500;
    private static final int DrainTimeoutSeconds = 20;

    private final Consumer<String, String> consumer;
    private final SmsCdrEventParser parser;
    private final CdrProcessor processor;
    private final ITenantRegistry registry;
    private final SmsOutgoingOptions opts;
    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-outgoing-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private SmsCdrKafkaConsumer(Consumer<String, String> consumer, SmsCdrEventParser parser, CdrProcessor processor,
            ITenantRegistry registry, SmsOutgoingOptions opts, Logger log) {
        this.consumer = consumer;
        this.parser = parser;
        this.processor = processor;
        this.registry = registry;
        this.opts = opts;
        this.log = log;
    }

    public static SmsCdrKafkaConsumer Start(CdrProcessor processor, ITenantRegistry registry, SmsOutgoingOptions opts,
            int fallbackSwitchId, Logger log) {
        String notRunnable = opts.NotRunnableReason();
        if (notRunnable != null) {
            log.infof("outgoing-SMS intake NOT started: %s", notRunnable);
            return null;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, opts.Group);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, opts.AutoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // commit MANUALLY, after the DB commit
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(opts.Topic));

        var loop = new SmsCdrKafkaConsumer(consumer, new SmsCdrEventParser(fallbackSwitchId), processor, registry, opts, log);
        loop.exec.submit(loop::run);
        log.infof("outgoing-SMS intake listening on topic '%s' (servers=%s, group=%s, tenant=%s, legacyDedup=%s)",
                opts.Topic, opts.BootstrapServers, opts.Group, opts.Tenant, opts.LegacyDedupEnabled);
        return loop;
    }

    private void run() {
        boolean warned = false;
        boolean waitedForConfig = false;
        try {
            while (running) {
                // Hold off until the tenant registry is loaded — polling earlier would fail every batch.
                if (!registry.IsLoaded()) {
                    if (!waitedForConfig) {
                        log.info("outgoing-SMS intake waiting for the first tenant-config load before consuming");
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
                    break;
                } catch (Exception ex) {
                    if (!warned) {
                        log.warnf("sms consume error (%s); retrying every %ds until it clears", ex.getMessage(), ErrorBackoffSeconds);
                        warned = true;
                    }
                    sleep(ErrorBackoffSeconds * 1000L);
                    continue;
                }
                if (records.isEmpty()) continue;

                try {
                    ProcessPollBatch(records);
                    consumer.commitSync();        // offsets AFTER the DB transaction committed
                } catch (Exception ex) {
                    log.error("sms poll-batch failed; rewinding for redelivery", ex);
                    SeekBackToBatchStart(records);
                    sleep(ErrorBackoffSeconds * 1000L);
                }
            }
        } catch (Exception fatal) {
            log.error("outgoing-SMS intake loop stopped on an unexpected error", fatal);
        } finally {
            try { consumer.close(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    /** Parse the poll-batch and bill it; throws when the tenant batch did not commit (→ rewind, no offset commit). */
    void ProcessPollBatch(ConsumerRecords<String, String> records) {
        List<String> values = new ArrayList<>();
        for (ConsumerRecord<String, String> rec : records)
            if (rec.value() != null) values.add(rec.value());
        ProcessValues(values, parser, processor, opts.Tenant, log);
    }

    /** The testable core: parse every value, dead-letter the unbillable, bill the rest as ONE SMS batch. */
    static int ProcessValues(List<String> values, SmsCdrEventParser parser, CdrProcessor processor, String tenant,
            Logger log) {
        var cdrs = new ArrayList<cdr>(values.size());
        for (String v : values) {
            var parsed = parser.Parse(v);
            if (parsed.Ok()) cdrs.add(parsed.Cdr());
            else log.warnf("sms dead-letter [%s]: %s", parsed.DeadLetterReason(), Truncate(v));
        }
        if (cdrs.isEmpty()) return 0;
        CdrProcessingResult r = processor.ProcessSmsOutgoingBatch(tenant, cdrs);
        if (!r.Committed())
            throw new IllegalStateException("sms batch for tenant '" + tenant + "' not committed: " + r.Error());
        return cdrs.size();
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
        boolean drained = GracefulDrain.drain(exec, DrainTimeoutSeconds, TimeUnit.SECONDS,
                () -> { try { consumer.wakeup(); } catch (Exception ignore) { /* best effort */ } });
        if (drained) log.info("outgoing-SMS intake drained cleanly (in-flight batch written + offsets committed)");
        else log.warnf("outgoing-SMS intake drain overran %ds; forced (in-flight batch redelivered on restart)", DrainTimeoutSeconds);
    }
}
