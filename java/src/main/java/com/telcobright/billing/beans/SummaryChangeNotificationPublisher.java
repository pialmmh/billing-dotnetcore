package com.telcobright.billing.beans;

import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jboss.logging.Logger;

import java.util.Properties;

/**
 * Best-effort Kafka change-notification for the decoupled summary path. After a cdr batch commits in OUTBOX
 * mode, this notifies "tenant X has a new batch for entity Y" so the summary-service processes it promptly
 * instead of waiting for its next poll. The message carries NO cdr data — the durable hand-off is the
 * {@code summary_affected} row. This is the only Kafka <i>producer</i> in the host.
 *
 * <p>{@link #Publish} NEVER throws and NEVER blocks the request: the outbox row is already committed and the
 * summary-service also polls, so a missed/failed ping costs only latency. If outbox mode is off or no broker
 * is configured, this is a no-op. (Port of Confluent.Kafka {@code IProducer<Null,string>}.)
 */
@Singleton
public class SummaryChangeNotificationPublisher {
    private static final Logger log = Logger.getLogger(SummaryChangeNotificationPublisher.class);

    private final Producer<String, String> producer;   // null => disabled / no broker
    private final String topic;

    @Inject
    public SummaryChangeNotificationPublisher(SummaryOutboxOptions opts) {
        this.topic = opts.PingTopic;
        Producer<String, String> p = null;
        if (opts.Enabled && opts.BootstrapServers != null && !opts.BootstrapServers.isBlank()) {
            try {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                p = new KafkaProducer<>(props);
                log.infof("summary change-notification publisher -> topic %s (servers=%s)", topic, opts.BootstrapServers);
            } catch (Exception ex) {
                log.warn("summary change-notification publisher init failed; notifications disabled (summary-service will poll)", ex);
            }
        }
        this.producer = p;
    }

    /** Fire-and-forget notification of a freshly committed outbox batch. Non-fatal on any failure. */
    public void Publish(String tenant, String entityType, int rows) {
        if (producer == null) return;
        try {
            producer.send(new ProducerRecord<>(topic, null,
                    "{\"tenant\":\"" + tenant + "\",\"entity\":\"" + entityType + "\",\"rows\":" + rows + "}"));
        } catch (Exception ex) {
            log.warnf(ex, "summary change-notification failed for tenant %s (non-fatal; summary-service polls)", tenant);
        }
    }

    @PreDestroy
    void dispose() {
        if (producer != null) {
            try { producer.flush(); } catch (Exception ignore) { /* best effort on shutdown */ }
            producer.close();
        }
    }
}
