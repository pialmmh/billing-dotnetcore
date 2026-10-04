package com.telcobright.billing.testsupport;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka as the summary ping meets it: a producer, and the brokers' answer to "is the topic there?" ({@link #IsThere},
 * the ping's {@code TopicCheck}) — with a topic that is on the brokers or is not.
 *
 * <p>While the topic is NOT there, {@code send} says so as the real producer does: through its callback and a failed
 * future, without throwing. Every call can be made to WAIT for the brokers first — as the real producer waits for a
 * missing topic's metadata inside {@code send()}, 60 s by default: a test holds the wait with {@link #HoldEveryCall()}
 * and ends it with {@link #LetGo()}. ALL the calls together never wait longer than {@link #LongestHoldMs} from that
 * moment — however many calls there are — so a rule that is broken shows as a red test and never as a hung one.
 */
public final class PingTopicProducer extends MockProducer<String, String> {
    public static final long LongestHoldMs = 20_000;

    public volatile boolean topicIsThere = true;
    /** false = the brokers do not answer the question at all (the check throws). */
    public volatile boolean brokersAnswer = true;
    private volatile CountDownLatch held;
    private volatile long heldUntilNanos;

    /** The pings the topic took. */
    public final List<ProducerRecord<String, String>> taken = new CopyOnWriteArrayList<>();
    /** How often the brokers were asked for the topic, how often a ping was handed to the producer, and how often
     * the PRODUCER was asked for the topic (it must never be: asked for a topic that is not there it keeps asking). */
    public final AtomicInteger asked = new AtomicInteger();
    public final AtomicInteger sends = new AtomicInteger();
    public final AtomicInteger producerAskedForTheTopic = new AtomicInteger();
    /** The names of the threads that ever called the brokers. */
    public final Set<String> callers = ConcurrentHashMap.newKeySet();

    public PingTopicProducer() {
        super(true, null, new StringSerializer(), new StringSerializer());
    }

    /** From now on every call waits for the brokers until {@link #LetGo()} — or until {@link #LongestHoldMs} from
     * now are over, whichever comes first. */
    public void HoldEveryCall() {
        heldUntilNanos = System.nanoTime() + LongestHoldMs * 1_000_000;
        held = new CountDownLatch(1);
    }

    public void LetGo() {
        CountDownLatch waitingOn = held;
        held = null;
        if (waitingOn != null) waitingOn.countDown();
    }

    private void WaitForTheBrokers() {
        CountDownLatch waitingOn = held;
        long leftNanos = heldUntilNanos - System.nanoTime();
        if (waitingOn == null || leftNanos <= 0) return;
        try {
            waitingOn.await(leftNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException closing) {
            Thread.currentThread().interrupt();
        }
    }

    private static TimeoutException NotThere(String topic) {
        return new TimeoutException("Topic " + topic + " not present in metadata after 60000 ms.");
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
        sends.incrementAndGet();
        callers.add(Thread.currentThread().getName());
        WaitForTheBrokers();
        CompletableFuture<RecordMetadata> answer = new CompletableFuture<>();
        if (!topicIsThere) {
            if (callback != null) callback.onCompletion(null, NotThere(record.topic()));
            answer.completeExceptionally(NotThere(record.topic()));
            return answer;
        }
        taken.add(record);
        RecordMetadata where = new RecordMetadata(new TopicPartition(record.topic(), 0), 0, taken.size() - 1, 0L, 0, 0);
        if (callback != null) callback.onCompletion(where, null);
        answer.complete(where);
        return answer;
    }

    /** The ping's question to the brokers: is the topic there? */
    public boolean IsThere() {
        asked.incrementAndGet();
        callers.add(Thread.currentThread().getName());
        WaitForTheBrokers();
        if (!brokersAnswer) throw new TimeoutException("Timed out waiting for a node assignment. Call: listTopics");
        return topicIsThere;
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        producerAskedForTheTopic.incrementAndGet();
        return List.of();
    }
}
