package com.telcobright.billing.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sends the records the preprocessor refused to the dead-letter topic of the ratified wire
 * ({@code cdr_dlq_<root tenant>}; the profile's {@code billing.cdr-ingest.dead-letter-topic}). Before this class a
 * refused record was only a log line and its offset was committed: the record was gone.
 *
 * <p><b>All or nothing.</b> {@link #Publish} answers {@code true} only when EVERY record of the batch was
 * acknowledged by the brokers ({@code acks=all}). It never throws; a record that was not acknowledged is one ERROR
 * line that names the topic, and the answer is {@code false}. What the caller then does is the rule of the ingest
 * (architect's ruling 2026-10-04): an offset is never committed past a record that is neither written nor
 * dead-lettered — see {@link CdrKafkaConsumer}. Publishing a batch a second time after a partial failure repeats
 * the records that had gone through: the topic is at-least-once, as the wire is.
 *
 * <p>Each value is one JSON object: {@code {"reason": "...", "record": "<the refused record, as text>"}}.
 */
public final class DeadLetterPublisher implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long SendTimeoutSeconds = 10;

    private final Producer<String, String> producer;
    private final String topic;
    private final Logger log;

    public DeadLetterPublisher(Producer<String, String> producer, String topic, Logger log) {
        this.producer = producer;
        this.topic = topic;
        this.log = log;
    }

    /** The publisher of a deployment: a producer on the ingest's own brokers, waiting for every in-sync replica. */
    public static DeadLetterPublisher ForTopic(String bootstrapServers, String topic, Logger log) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(SendTimeoutSeconds * 1000));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Long.toString(SendTimeoutSeconds * 1000));
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Long.toString(SendTimeoutSeconds * 1000 / 2));
        return new DeadLetterPublisher(new KafkaProducer<>(props), topic, log);
    }

    public String Topic() {
        return topic;
    }

    /** Send every dead letter and wait for the brokers. {@code true} = all of them are on the topic (or there were none). */
    public boolean Publish(List<DeadLetteredCdr> deadLetters) {
        if (deadLetters.isEmpty()) return true;
        List<Future<?>> sent = SendAll(deadLetters);
        return sent.size() == deadLetters.size() && AllAcknowledged(sent, deadLetters);
    }

    /** Hand every record to the producer. The first one it refuses ends the round: the rest would only wait too. */
    private List<Future<?>> SendAll(List<DeadLetteredCdr> deadLetters) {
        List<Future<?>> sent = new ArrayList<>(deadLetters.size());
        for (DeadLetteredCdr d : deadLetters) {
            try {
                sent.add(producer.send(new ProducerRecord<>(topic, null, ValueOf(d))));
            } catch (Exception ex) {
                log.errorf(ex, "cdr dead letter NOT published to topic '%s' [%s]: %s", topic, d.reason(), d.payload());
                break;
            }
        }
        return sent;
    }

    private boolean AllAcknowledged(List<Future<?>> sent, List<DeadLetteredCdr> deadLetters) {
        boolean all = true;
        for (int i = 0; i < sent.size(); i++) {
            try {
                sent.get(i).get(SendTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ex) {
                all = false;
                log.errorf(ex, "cdr dead letter NOT published to topic '%s' [%s]: %s",
                        topic, deadLetters.get(i).reason(), deadLetters.get(i).payload());
            }
        }
        return all;
    }

    /** {@code {"reason": …, "record": …}} — the record as the text the preprocessor kept (JSON, or the raw value). */
    static String ValueOf(DeadLetteredCdr d) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("reason", d.reason() == null ? "" : d.reason());
        value.put("record", d.payload() == null ? "" : d.payload());
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"reason\":\"unserialisable dead letter\",\"record\":\"\"}";
        }
    }

    @Override
    public void close() {
        try { producer.close(java.time.Duration.ofSeconds(SendTimeoutSeconds)); } catch (Exception ignore) { /* best effort */ }
    }
}
