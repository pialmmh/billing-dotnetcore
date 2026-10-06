package com.telcobright.billing.tenantconfigsync.dependencies;

import com.telcobright.billing.ingest.sms.SmsCdrKafkaConsumer;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** billing.mediation.sms-outgoing: parsing + the fail-safe start rule (no invented topic/group). */
class SmsOutgoingOptionsTests {

    private static final String Full = """
            billing:
              cdr-ingest:
                bootstrap-servers: "broker-a:9092"
              mediation:
                switch-id: 1
                sms-outgoing:
                  enabled: true
                  tenant: "sms_tenant"
                  topic: "the_topic"
                  group: "the_group"
            """;

    @Test
    void absent_block_is_disabled() {
        var o = ProfileConfigReader.ReadSmsOutgoingFromYaml("billing:\n  mediation:\n    switch-id: 1\n");
        assertFalse(o.Enabled);
        assertNotNull(o.NotRunnableReason());
        assertTrue(o.LegacyDedupEnabled, "legacy ownership protection defaults ON for SMS");
    }

    @Test
    void full_block_is_runnable_and_falls_back_to_the_cdr_ingest_brokers() {
        var o = ProfileConfigReader.ReadSmsOutgoingFromYaml(Full);
        assertNull(o.NotRunnableReason());
        assertEquals("sms_tenant", o.Tenant);
        assertEquals("the_topic", o.Topic);
        assertEquals("the_group", o.Group);
        assertEquals("broker-a:9092", o.BootstrapServers);
        assertEquals("latest", o.AutoOffsetReset);
        assertTrue(o.LegacyDedupEnabled);
        assertTrue(o.IsSmsTenant("sms_tenant"));
        assertFalse(o.IsSmsTenant("telcobright"));
    }

    @Test
    void enabled_without_topic_or_group_or_tenant_stays_disabled() {
        assertNotNull(ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace("topic: \"the_topic\"", "topic: \"\"")).NotRunnableReason());
        assertNotNull(ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace("group: \"the_group\"", "group: \"\"")).NotRunnableReason());
        assertNotNull(ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace("tenant: \"sms_tenant\"", "tenant: \"\"")).NotRunnableReason());
        assertNotNull(ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace("bootstrap-servers: \"broker-a:9092\"", "bootstrap-servers: \"\"")).NotRunnableReason());
    }

    @Test
    void explicit_overrides_are_read() {
        var o = ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace("group: \"the_group\"", """
                group: "the_group"
                      bootstrap-servers: "broker-b:9092"
                      legacy-dedup-enabled: false
                      id-block-size: 10
                      poll-ms: 250
                      auto-offset-reset: earliest"""));
        assertEquals("broker-b:9092", o.BootstrapServers);
        assertFalse(o.LegacyDedupEnabled);
        assertEquals(10, o.IdBlockSize);
        assertEquals(250, o.PollMs);
        assertEquals("earliest", o.AutoOffsetReset);
        assertNull(o.NotRunnableReason());
    }

    @Test
    void the_consumer_does_not_start_when_not_runnable() {
        var o = new SmsOutgoingOptions();              // disabled by default
        assertNull(SmsCdrKafkaConsumer.Start(null, null, o, 0, Logger.getLogger(SmsOutgoingOptionsTests.class)));
        o.Enabled = true;                              // enabled but no topic/group/tenant/brokers
        assertNull(SmsCdrKafkaConsumer.Start(null, null, o, 0, Logger.getLogger(SmsOutgoingOptionsTests.class)));
    }

    @Test
    void application_start_alone_never_starts_the_sms_consumer_any_missing_setting_keeps_it_disabled() {
        var log = Logger.getLogger(SmsOutgoingOptionsTests.class);
        // a profile with NO sms-outgoing block — what a plain application start sees
        assertNull(SmsCdrKafkaConsumer.Start(null, null,
                ProfileConfigReader.ReadSmsOutgoingFromYaml("billing:\n  cdr-ingest:\n    bootstrap-servers: \"broker-a:9092\"\n"), 0, log));
        // fully configured except ONE setting -> still disabled (no consumer, no guessed/default topic or group)
        String[][] drops = {
                {"enabled: true", "enabled: false"},
                {"tenant: \"sms_tenant\"", "tenant: \"\""},
                {"topic: \"the_topic\"", "topic: \"\""},
                {"group: \"the_group\"", "group: \"\""},
                {"bootstrap-servers: \"broker-a:9092\"", "bootstrap-servers: \"\""},
        };
        for (String[] d : drops) {
            var o = ProfileConfigReader.ReadSmsOutgoingFromYaml(Full.replace(d[0], d[1]));
            assertNotNull(o.NotRunnableReason(), "missing " + d[0]);
            assertNull(SmsCdrKafkaConsumer.Start(null, null, o, 0, log), "must not start without " + d[0]);
        }
        var defaults = new SmsOutgoingOptions();
        assertEquals("", defaults.Topic, "no default topic is ever assumed");
        assertEquals("", defaults.Group, "no default consumer group is ever assumed");
        assertFalse(defaults.Enabled);
    }

    @Test
    void the_existing_voice_profile_is_unaffected() {
        // an existing voice profile carries `mediation: {voice-enabled, sms-enabled}` — still parses, SMS stays off
        var o = ProfileConfigReader.ReadSmsOutgoingFromYaml("billing:\n  mediation:\n    voice-enabled: true\n    sms-enabled: false\n");
        assertFalse(o.Enabled);
        assertEquals(0, ProfileConfigReader.ReadMediationFromYaml("billing:\n  mediation:\n    voice-enabled: true\n").SwitchId);
    }
}
