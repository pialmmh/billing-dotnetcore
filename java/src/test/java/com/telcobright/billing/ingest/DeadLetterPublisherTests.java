package com.telcobright.billing.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 — the dead letters of the ratified wire ({@code cdr_dlq_<root tenant>}): a refused record is PUBLISHED, not
 * only logged, and the publisher answers "all of them are on the topic" or "not" — never a half truth. What the
 * ingest does with a "not" is {@link CdrKafkaConsumerTests}.
 */
class DeadLetterPublisherTests {
    private static final Logger LOG = Logger.getLogger(DeadLetterPublisherTests.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static MockProducer<String, String> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new StringSerializer(), new StringSerializer());
    }

    private static final List<DeadLetteredCdr> TWO = List.of(
            new DeadLetteredCdr("{\"tenant\":\"res_9\",\"callId\":\"c-1\"}", "unknown tenant 'res_9'"),
            new DeadLetteredCdr("{ this is not json", "decode failed: Unexpected character"));

    @Test
    void each_refused_record_goes_to_the_dead_letter_topic_with_its_reason() throws Exception {
        MockProducer<String, String> kafka = producer(true);
        var publisher = new DeadLetterPublisher(kafka, "cdr_dlq_btcl", LOG);

        boolean allOnTheTopic = publisher.Publish(TWO);

        assertTrue(allOnTheTopic);
        List<ProducerRecord<String, String>> sent = kafka.history();
        assertEquals(2, sent.size());
        assertEquals("cdr_dlq_btcl", sent.get(0).topic());
        assertNull(sent.get(0).key());
        JsonNode first = JSON.readTree(sent.get(0).value());
        assertEquals("unknown tenant 'res_9'", first.get("reason").asText());
        assertEquals("{\"tenant\":\"res_9\",\"callId\":\"c-1\"}", first.get("record").asText());   // the record, as text
        JsonNode second = JSON.readTree(sent.get(1).value());
        assertEquals("{ this is not json", second.get("record").asText());                     // even one that never decoded
    }

    @Test
    void no_dead_letter_is_a_yes_and_sends_nothing() {
        MockProducer<String, String> kafka = producer(true);

        assertTrue(new DeadLetterPublisher(kafka, "cdr_dlq_btcl", LOG).Publish(List.of()));
        assertEquals(0, kafka.history().size());
    }

    @Test
    void a_send_the_producer_refuses_is_a_no_and_never_an_exception() {
        MockProducer<String, String> kafka = producer(true);
        kafka.sendException = new org.apache.kafka.common.errors.TimeoutException("topic cdr_dlq_btcl not present in metadata");

        boolean allOnTheTopic = new DeadLetterPublisher(kafka, "cdr_dlq_btcl", LOG).Publish(TWO);

        assertFalse(allOnTheTopic);
    }

    @Test
    void one_record_the_brokers_do_not_acknowledge_makes_the_whole_batch_a_no() {
        MockProducer<String, String> kafka = producer(false);          // sends stay pending until completed by hand
        var publisher = new DeadLetterPublisher(kafka, "cdr_dlq_btcl", LOG);
        Thread brokers = new Thread(() -> {
            while (kafka.history().size() < 2) Thread.onSpinWait();
            kafka.completeNext();                                                          // the first is acknowledged
            kafka.errorNext(new org.apache.kafka.common.errors.NotEnoughReplicasException("acks=all not met"));
        });
        brokers.start();

        boolean allOnTheTopic = publisher.Publish(TWO);

        assertFalse(allOnTheTopic, "one of two is not 'all'");
    }

    @Test
    void the_value_is_one_json_object_whatever_the_record_holds() throws Exception {
        String value = DeadLetterPublisher.ValueOf(new DeadLetteredCdr("a \"quoted\" \\ record\n", null));

        JsonNode node = JSON.readTree(value);
        assertTrue(node.isObject());
        assertEquals("", node.get("reason").asText());
        assertEquals("a \"quoted\" \\ record\n", node.get("record").asText());
    }
}
