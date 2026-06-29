package com.telcobright.billing.tenantconfigsync.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.tenantconfigsync.dependencies.ConfigEventsOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.spi.ConfigChangeSignal;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Kafka consume machinery behind {@link KafkaConfigEventSource}: builds the consumer, subscribes to each
 * enabled tenant's {@code config_event_loader_<tenant>} topic, and polls on a background thread — turning each
 * "tenant X changed" message into a {@link ConfigChangeSignal}. A consume error (e.g. the topic doesn't exist
 * yet) is logged ONCE, then retried with a backoff. This is the encapsulated detail; the source surface only
 * starts/stops it. (Port of the Confluent.Kafka consume loop.)
 */
final class ConfigEventConsumerLoop {
    private static final int ErrorBackoffSeconds = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Consumer<String, String> consumer;
    private final ConfigEventsOptions opts;
    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "config-event-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private ConfigEventConsumerLoop(Consumer<String, String> consumer, ConfigEventsOptions opts, Logger log) {
        this.consumer = consumer;
        this.opts = opts;
        this.log = log;
    }

    /** Build the consumer, subscribe, and launch the poll loop. Returns {@code null} when no tenants are
     * enabled (nothing to listen to) — the caller then stays idle. */
    static ConfigEventConsumerLoop Start(TenantSelection selection, ConfigEventsOptions opts, Logger log,
            Function<ConfigChangeSignal, CompletableFuture<Void>> onSignal) {
        List<String> topics = selection.Enabled().stream()
                .map(t -> opts.EventTopicBase + "_" + t.Name())
                .distinct()
                .collect(Collectors.toList());
        if (topics.isEmpty()) {
            log.info("no enabled tenants — Kafka config-event source idle");
            return null;
        }

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, opts.ConsumerGroupBase);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(topics);

        ConfigEventConsumerLoop loop = new ConfigEventConsumerLoop(consumer, opts, log);
        loop.exec.submit(() -> loop.run(onSignal));
        log.infof("Kafka config-event source listening on %s (servers=%s)",
                String.join(",", topics), opts.BootstrapServers);
        return loop;
    }

    private void run(Function<ConfigChangeSignal, CompletableFuture<Void>> onSignal) {
        boolean warned = false;   // log a recurring error ONCE, not on every poll
        try {
            while (running) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                    warned = false;   // healthy again
                } catch (WakeupException we) {
                    break;            // normal shutdown
                } catch (Exception ex) {
                    // e.g. the topic does not exist yet — expected until config-manager creates/publishes to
                    // it. Log once, then back off and retry quietly instead of spamming every poll.
                    if (!warned) {
                        log.warnf("Kafka consume error (%s); retrying every %ds until it clears",
                                ex.getMessage(), ErrorBackoffSeconds);
                        warned = true;
                    }
                    sleep(ErrorBackoffSeconds * 1000L);
                    continue;
                }
                for (ConsumerRecord<String, String> rec : records) {
                    if (rec.value() == null) continue;
                    onSignal.apply(ParseSignal(TenantFromTopic(rec.topic()), rec.value()));
                }
            }
        } catch (Exception e) {
            /* shutdown */
        } finally {
            try { consumer.close(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private String TenantFromTopic(String topic) {
        String prefix = opts.EventTopicBase + "_";
        return topic.startsWith(prefix) ? topic.substring(prefix.length()) : topic;
    }

    private static ConfigChangeSignal ParseSignal(String tenant, String json) {
        try {
            JsonNode root = JSON.readTree(json);
            int count = root.has("changeCount") && root.get("changeCount").canConvertToInt()
                    ? root.get("changeCount").asInt() : 1;
            String eventId = root.has("eventId") && !root.get("eventId").isNull()
                    ? root.get("eventId").asText() : null;
            return new ConfigChangeSignal(tenant, count, eventId);
        } catch (Exception e) {
            return new ConfigChangeSignal(tenant, 1, null);
        }
    }

    void stop() {
        running = false;
        try { consumer.wakeup(); } catch (Exception ignore) { /* best effort */ }
        exec.shutdownNow();
    }
}
