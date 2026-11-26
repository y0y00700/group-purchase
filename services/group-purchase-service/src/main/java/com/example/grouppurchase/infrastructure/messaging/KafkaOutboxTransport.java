package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.config.OutboxSettings;
import com.example.grouppurchase.outbox.infrastructure.JdbcOutbox.Claim;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** One lazy producer per service; an empty outbox never contacts Kafka. */
public final class KafkaOutboxTransport implements OutboxTransport {
    private final OutboxSettings settings;
    private KafkaProducer<String, byte[]> producer;
    public KafkaOutboxTransport(OutboxSettings settings) { this.settings = settings; }

    private synchronized KafkaProducer<String, byte[]> producer() {
        if (producer == null) {
            var p = new Properties();
            p.put("bootstrap.servers", settings.bootstrap());
            p.put("acks", "all"); p.put("enable.idempotence", "true");
            p.put("retries", Integer.toString(Integer.MAX_VALUE)); p.put("max.in.flight.requests.per.connection", "5");
            p.put("max.block.ms", Long.toString(settings.blockMs()));
            p.put("request.timeout.ms", Integer.toString(settings.requestMs()));
            p.put("delivery.timeout.ms", Integer.toString(settings.deliveryMs())); p.put("linger.ms", "0");
            p.put("key.serializer", StringSerializer.class.getName()); p.put("value.serializer", ByteArraySerializer.class.getName());
            producer = new KafkaProducer<>(p);
        }
        return producer;
    }

    @Override public Ack send(Claim message) throws Exception {
        var decoded = new MessageCodec().receive(message.raw(), message.topic(), message.key());
        if (!decoded.envelope().get("messageId").asString().equals(message.messageId().toString())
                || (message.sequence() != null && decoded.envelope().path("sequence").longValue() != message.sequence())) {
            throw new IllegalArgumentException("Stored metadata mismatch");
        }
        var metadata = producer().send(new ProducerRecord<>(message.topic(), message.key(), message.raw()))
                .get(settings.waitMs(), TimeUnit.MILLISECONDS);
        return new Ack(metadata.partition(), metadata.offset());
    }

    @Override public synchronized void close() {
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }
}
