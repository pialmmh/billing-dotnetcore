package com.telcobright.billing.beans;

import com.telcobright.billing.ingest.IngestHealth;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The summary PING: after a cdr batch commits, "tenant X has a new batch for entity Y" on a Kafka topic, so the
 * summary service looks at once instead of at its next poll. The message carries NO cdr data — the durable hand-off
 * is the {@code summary_affected} row, written in the batch's own transaction. A ping that is never sent costs
 * latency only.
 *
 * <p><b>The ping never holds a batch.</b> {@link #Publish} hands the ping to the ping's OWN thread and returns. That
 * thread is the only one that talks to the brokers, and the only one that waits for them. (A Kafka producer waits for
 * the topic's metadata inside {@code send()} — 60 s by default when the topic is not there — and it used to wait on
 * the ingest's thread, once per tier per batch, with nothing in the log but the Kafka client's own lines.)
 *
 * <p><b>A topic that is not on the brokers is never handed a ping.</b> The brokers are asked for their topics
 * ({@link TopicCheck}) at start and, while the topic does not take pings, once a minute; the pings in between are
 * dropped untried (a ping is a nudge, not data: they do not pile up). The producer is not asked: a producer that is
 * asked for a topic the brokers do not have keeps asking in the background and fills the log with the client's own
 * WARN lines. billing-core creates no topic, and the ping does not make a broker create one either.
 *
 * <p><b>A ping that cannot be published is said, by this class:</b> ONE WARN a minute that names the topic and the
 * brokers, and a detail on the health road ({@link IngestHealth#PingNotPublished}) — which stays UP: the rows are
 * written and the summary service polls.
 *
 * <p>If the ping is switched off or the profile names no broker, this is a no-op and no thread is started.
 */
@Singleton
public class SummaryChangeNotificationPublisher {
    private static final Logger log = Logger.getLogger(SummaryChangeNotificationPublisher.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();   // proper escaping (no hand-built JSON)

    /** Is the ping's topic on the brokers? May wait for them ({@link #BrokerWaitMs}); throws when they do not answer. */
    @FunctionalInterface
    public interface TopicCheck {
        boolean IsThere() throws Exception;
    }

    /** Pings that wait for the ping's thread. When it is full the newest ping is dropped, and that is said. */
    static final int QueueCapacity = 256;
    /** How long the ping's own thread may wait for the brokers in one call. Generous: nothing waits behind it, and a
     * first call at a cold start under load needs seconds (3 s was not enough on the lab: "the brokers do not answer"
     * for a minute, about brokers that were fine). */
    static final long BrokerWaitMs = 10_000;
    static final long WarnEveryMs = 60_000;
    /** A topic that does not take pings is asked for again this often; pings in between are dropped untried. */
    static final long AskAgainEveryMs = 60_000;
    static final String NotOnTheBrokers = "the topic is not on the brokers";
    private static final long IdleTickMs = 500;
    private static final long Never = Long.MIN_VALUE;

    private final Producer<String, String> producer;   // null => switched off / no broker
    private final TopicCheck topicIsOnTheBrokers;
    private final AutoCloseable alsoClosed;             // the client that lists the topics (a deployment's)
    private final String topic;
    private final String brokers;
    private final IngestHealth health;
    private final Consumer<String> warn;
    private final LongSupplier clockMs;

    private final BlockingQueue<String> waiting = new ArrayBlockingQueue<>(QueueCapacity);
    private final Thread sender;
    private final AtomicLong lastWarnAt = new AtomicLong(Never);
    private final AtomicInteger notSentSinceTheLastLine = new AtomicInteger();
    private volatile boolean stopped;
    private volatile boolean topicTakesPings = true;
    private volatile long askAgainAt;
    private volatile String lastCause = "";

    @Inject
    public SummaryChangeNotificationPublisher(SummaryOutboxOptions opts, IngestHealth health) {
        this(KafkaOf(opts), opts, health);
    }

    /** For a caller with no health road of its own (a test): the ping's trouble is then only logged. */
    public SummaryChangeNotificationPublisher(SummaryOutboxOptions opts) {
        this(opts, new IngestHealth());
    }

    private SummaryChangeNotificationPublisher(Kafka kafka, SummaryOutboxOptions opts, IngestHealth health) {
        this(kafka.producer, kafka.TopicCheckOf(opts.PingTopic), kafka.admin, opts.PingTopic, opts.BootstrapServers, health,
                log::warn, System::currentTimeMillis);
        if (producer != null) log.infof("summary change-notification publisher -> topic %s (servers=%s)", topic, brokers);
    }

    /** On a given producer ({@code null} = switched off). {@code warn} takes this class's own WARN lines. */
    public SummaryChangeNotificationPublisher(Producer<String, String> producer, TopicCheck topicIsOnTheBrokers,
            AutoCloseable alsoClosed, String topic, String brokers, IngestHealth health, Consumer<String> warn,
            LongSupplier clockMs) {
        this.producer = producer;
        this.topicIsOnTheBrokers = topicIsOnTheBrokers;
        this.alsoClosed = alsoClosed;
        this.topic = topic;
        this.brokers = brokers;
        this.health = health;
        this.warn = warn;
        this.clockMs = clockMs;
        this.sender = producer == null ? null : StartThePingsOwnThread();
    }

    /** A deployment's two clients: the producer the pings go through, and the one that lists the brokers' topics. */
    private record Kafka(Producer<String, String> producer, Admin admin) {
        TopicCheck TopicCheckOf(String topic) {
            return admin == null ? null
                    : () -> admin.listTopics().names().get(BrokerWaitMs, TimeUnit.MILLISECONDS).contains(topic);
        }
    }

    /** The clients of a deployment; none when the ping is switched off or the profile names no broker. */
    private static Kafka KafkaOf(SummaryOutboxOptions opts) {
        if (!opts.Enabled || opts.BootstrapServers == null || opts.BootstrapServers.isBlank()) return new Kafka(null, null);
        try {
            Properties producing = new Properties();
            producing.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
            producing.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producing.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producing.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(BrokerWaitMs));
            Properties listing = new Properties();
            listing.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, opts.BootstrapServers);
            listing.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Long.toString(BrokerWaitMs));
            listing.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Long.toString(BrokerWaitMs));
            return new Kafka(new KafkaProducer<>(producing), Admin.create(listing));
        } catch (Exception ex) {
            log.warn("summary change-notification publisher init failed; notifications disabled (summary-service will poll)", ex);
            return new Kafka(null, null);
        }
    }

    private Thread StartThePingsOwnThread() {
        Thread thread = new Thread(this::SendWhatWaits, "summary-ping");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Is the ping switched on (a producer, and its own thread)? */
    public boolean IsOn() {
        return producer != null;
    }

    /** Was the ping's own thread started? (Not when the ping is switched off.) */
    boolean HasItsOwnThread() {
        return sender != null;
    }

    /** How many pings wait for the ping's thread. */
    int Waiting() {
        return waiting.size();
    }

    /** How many pings were not sent since this class's last WARN line (the number that line will carry). */
    int NotSentSinceTheLastLine() {
        return notSentSinceTheLastLine.get();
    }

    /**
     * Hand a ping to the ping's own thread and return. It NEVER waits for the brokers on the caller's thread, and it
     * never throws: the batch is committed, and the summary service polls.
     */
    public void Publish(String tenant, String entityType, int rows) {
        if (producer == null) return;
        if (!waiting.offer(PayloadOf(tenant, entityType, rows)))
            NotSent(lastCause.isEmpty() ? "its queue of " + QueueCapacity + " is full" : lastCause);
    }

    private static String PayloadOf(String tenant, String entityType, int rows) {
        try {
            return JSON.writeValueAsString(java.util.Map.of(
                    "tenant", tenant == null ? "" : tenant,
                    "entity", entityType == null ? "" : entityType,
                    "rows", rows));
        } catch (Exception unserialisable) {
            return "{}";
        }
    }

    // ── the ping's own thread: the only one that talks to the brokers ────────────────────────────────────────

    private void SendWhatWaits() {
        AskForTheTopic();                                   // at start: the health road knows before the first batch
        while (!stopped) {
            String ping = NextPingOrNull();
            if (ping != null) SendOrDrop(ping);
            else if (!topicTakesPings && MayAskAgain()) AskForTheTopic();
        }
    }

    private String NextPingOrNull() {
        try {
            return waiting.poll(IdleTickMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            stopped = true;
            return null;
        }
    }

    private boolean MayAskAgain() {
        return clockMs.getAsLong() >= askAgainAt;
    }

    /** A ping for a topic that does not take them is dropped untried — until the topic may be asked for again. */
    private void SendOrDrop(String ping) {
        if (!topicTakesPings && MayAskAgain()) AskForTheTopic();
        if (topicTakesPings) Send(ping);
        else NotSent(lastCause);
    }

    /** One send, to a topic the brokers have. A producer says "not taken" through the callback, not by throwing:
     * both ways are heard. */
    private void Send(String ping) {
        try {
            producer.send(new ProducerRecord<>(topic, null, ping), (sent, failure) -> {
                if (failure == null) TheTopicTakesPings();
                else APingWasNotTaken(WordsOf(failure));
            });
        } catch (Exception ex) {
            APingWasNotTaken(WordsOf(ex));
        }
    }

    /** Ask the brokers for their topics — not the producer: at start, and once a minute while the topic does not
     * take pings. */
    private void AskForTheTopic() {
        try {
            if (topicIsOnTheBrokers.IsThere()) TheTopicTakesPings();
            else TheTopicDoesNotTakePings(NotOnTheBrokers);
        } catch (Exception ex) {
            TheTopicDoesNotTakePings("the brokers do not answer (" + WordsOf(ex) + ")");
        }
    }

    private static String WordsOf(Throwable failure) {
        Throwable said = failure.getCause() != null && failure instanceof java.util.concurrent.ExecutionException
                ? failure.getCause() : failure;
        String message = said.getMessage() == null ? "" : said.getMessage().trim();
        while (message.endsWith(".")) message = message.substring(0, message.length() - 1);
        return said.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    // ── what is said about it ────────────────────────────────────────────────────────────────────────────────

    private void TheTopicTakesPings() {
        if (topicTakesPings && health.PingDetail().isEmpty()) return;
        topicTakesPings = true;
        lastCause = "";
        health.PingPublished();
        log.infof("summary ping: topic '%s' on %s takes pings again", topic, brokers);
    }

    private void TheTopicDoesNotTakePings(String cause) {
        topicTakesPings = false;
        lastCause = cause;
        askAgainAt = clockMs.getAsLong() + AskAgainEveryMs;
        Say(cause);
    }

    private void APingWasNotTaken(String cause) {
        notSentSinceTheLastLine.incrementAndGet();
        TheTopicDoesNotTakePings(cause);
    }

    private void NotSent(String cause) {
        notSentSinceTheLastLine.incrementAndGet();
        Say(cause);
    }

    /** The detail on the health road, always; the WARN, once a minute. */
    private void Say(String cause) {
        health.PingNotPublished("the summary ping is not published: topic '" + topic + "' on " + brokers + " — " + cause
                + ". The rows are written and the summary service polls: summaries come later, nothing is lost");
        long now = clockMs.getAsLong();
        long last = lastWarnAt.get();
        if (last != Never && now - last < WarnEveryMs) return;
        if (!lastWarnAt.compareAndSet(last, now)) return;            // another thread wrote this minute's line
        int notSent = notSentSinceTheLastLine.getAndSet(0);
        warn.accept("summary ping NOT published to topic '" + topic + "' on " + brokers + " — " + cause
                + (notSent > 0 ? " (" + notSent + " ping(s) not sent since the last line)" : "")
                + ". Nothing is lost: the rows and the outbox row are written and the summary service polls; its summaries"
                + " come later. billing-core creates no topic: make it, and the pings start by themselves within a minute."
                + " This line comes once a minute.");
    }

    @PreDestroy
    void dispose() {
        stopped = true;
        if (sender != null) sender.interrupt();
        if (producer != null) {
            try { producer.close(Duration.ofSeconds(5)); } catch (Exception ignore) { /* best effort on shutdown */ }
        }
        if (alsoClosed != null) {
            try { alsoClosed.close(); } catch (Exception ignore) { /* best effort on shutdown */ }
        }
    }
}
