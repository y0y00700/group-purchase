package com.example.grouppurchase.kafkaprobe;

import com.example.grouppurchase.infrastructure.messaging.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;

/** Verification-only transport. No DB effect, outbox, retry policy or product listener. */
public final class ProbeTransport {
    private static final MessageCodec CODEC = new MessageCodec();
    private ProbeTransport() { }

    public static Properties producerProperties(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        p.put("acks", "all"); p.put("enable.idempotence", "true");
        p.put("max.in.flight.requests.per.connection", "5"); p.put("retries", "2147483647");
        p.put("request.timeout.ms", "10000"); p.put("delivery.timeout.ms", "30000");
        p.put("max.block.ms", "10000"); p.put("linger.ms", "0");
        return p;
    }

    public static KafkaConsumer<String, byte[]> consumer(String bootstrap, String group) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap); p.put("group.id", group);
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("enable.auto.commit", "false"); p.put("allow.auto.create.topics", "false");
        p.put("auto.offset.reset", "earliest"); p.put("default.api.timeout.ms", "10000");
        p.put("request.timeout.ms", "10000"); p.put("session.timeout.ms", "10000");
        p.put("heartbeat.interval.ms", "2000");
        return new KafkaConsumer<>(p);
    }

    public static RecordMetadata publish(KafkaProducer<String, byte[]> producer,
                                         String topic, String key, byte[] value) throws Exception {
        RecordMetadata saved = producer.send(new ProducerRecord<>(topic, key, value)).get(35, TimeUnit.SECONDS);
        System.out.printf("ACK topic=%s key=%s partition=%d offset=%d%n", topic, key, saved.partition(), saved.offset());
        return saved;
    }

    /** logicalTopic maps isolated verification topics to their v1 business contract. */
    public static void validateAndCommit(KafkaConsumer<String, byte[]> consumer,
            ConsumerRecord<String, byte[]> record, String logicalTopic, boolean commit) {
        try {
            CODEC.receive(record.value(), logicalTopic, record.key());
        } catch (ContractFailure invalid) {
            System.err.printf("REJECT topic=%s partition=%d offset=%d code=%s rawBase64=%s%n",
                    record.topic(), record.partition(), record.offset(), invalid.code(),
                    record.value() == null ? "<null>" : Base64.getEncoder().encodeToString(record.value()));
            throw invalid;
        }
        System.out.printf("RECEIVE topic=%s key=%s partition=%d offset=%d position=%d%n",
                record.topic(), record.key(), record.partition(), record.offset(),
                consumer.position(new TopicPartition(record.topic(), record.partition())));
        if (commit) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(10));
            System.out.printf("COMMIT topic=%s partition=%d nextOffset=%d%n", record.topic(), record.partition(), record.offset() + 1);
        }
    }

    public static void verifyRestartOffset(KafkaConsumer<String, byte[]> consumer,
            TopicPartition partition, Long expectedCommitted, long expectedNext) {
        OffsetAndMetadata committed = consumer.committed(Set.of(partition), Duration.ofSeconds(10)).get(partition);
        Long actual = committed == null ? null : committed.offset();
        if (!Objects.equals(expectedCommitted, actual)) throw new IllegalStateException("committed " + actual + " != " + expectedCommitted);
        long position = consumer.position(partition, Duration.ofSeconds(10));
        if (position != expectedNext) throw new IllegalStateException("position " + position + " != " + expectedNext);
        System.out.printf("RESTART topic=%s partition=%d committed=%s nextPosition=%d%n",
                partition.topic(), partition.partition(), actual, position);
    }

}
