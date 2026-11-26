package com.example.grouppurchase.config;

import java.util.*;
import org.springframework.core.env.Environment;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

public record InboxSettings(boolean enabled,String bootstrap,String topic,String group,String role,String bootstrapMode,
        String expectedTopicId,int partitions,int maxPollRecords) {
    public InboxSettings {
        if(bootstrap==null || bootstrap.isBlank() || topic==null || topic.isBlank() || group==null || group.isBlank()
                || role==null || role.isBlank() || partitions<1 || maxPollRecords<1 || maxPollRecords>500
                || !Set.of("disabled","from-origin").contains(bootstrapMode) || (enabled && (expectedTopicId==null || expectedTopicId.isBlank())))
            throw new IllegalArgumentException("Invalid inbox identity/bootstrap settings");
    }
    public static InboxSettings from(Environment e) {
        return new InboxSettings(e.getProperty("app.inbox.enabled",Boolean.class,false),e.getProperty("app.messaging.kafka.bootstrap-servers","localhost:9092"),
                "gp.payment.results.v1","group-purchase-service.payment-results.v1","purchase-payment-result-handler",
                e.getProperty("app.inbox.bootstrap-mode","disabled"),e.getProperty("app.inbox.expected-topic-id",""),
                e.getProperty("app.inbox.expected-partitions",Integer.class,3),1);
    }
    public Map<String,Object> consumerProperties() {
        return Map.ofEntries(Map.entry("bootstrap.servers",bootstrap),Map.entry("group.id",group),Map.entry("enable.auto.commit",false),
                Map.entry("allow.auto.create.topics",false),Map.entry("auto.offset.reset","none"),Map.entry("group.protocol","classic"),
                Map.entry("max.poll.records",maxPollRecords),Map.entry("max.poll.interval.ms",300000),Map.entry("session.timeout.ms",45000),
                Map.entry("heartbeat.interval.ms",3000),Map.entry("default.api.timeout.ms",5000),Map.entry("request.timeout.ms",10000),
                Map.entry("key.deserializer",ByteArrayDeserializer.class),Map.entry("value.deserializer",ByteArrayDeserializer.class));
    }
}
