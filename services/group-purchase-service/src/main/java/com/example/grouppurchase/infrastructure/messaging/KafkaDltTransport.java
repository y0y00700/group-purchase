package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.config.OutboxSettings;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

public final class KafkaDltTransport implements DltTransport {
    public static final int MAX_BYTES=4194304;
    private final OutboxSettings settings;
    private final String topic;
    private KafkaProducer<String,byte[]> producer;
    public KafkaDltTransport(OutboxSettings settings,String topic) { this.settings=settings; this.topic=topic; }
    private synchronized KafkaProducer<String,byte[]> producer() {
        if(producer==null) producer=new KafkaProducer<>(Map.ofEntries(
                Map.entry("bootstrap.servers",settings.bootstrap()),Map.entry("acks","all"),Map.entry("enable.idempotence",true),
                Map.entry("max.block.ms",settings.blockMs()),Map.entry("request.timeout.ms",settings.requestMs()),
                Map.entry("delivery.timeout.ms",settings.deliveryMs()),Map.entry("max.request.size",MAX_BYTES),Map.entry("linger.ms",0),
                Map.entry("key.serializer",StringSerializer.class),Map.entry("value.serializer",ByteArraySerializer.class)));
        return producer;
    }
    @Override public Ack send(UUID failureId,byte[] document) throws Exception {
        // Reserve room for key, record and batch framing, rather than sending a truncated document.
        if(document.length>MAX_BYTES-1024) throw new org.apache.kafka.common.errors.RecordTooLargeException("DLT_SIZE_LIMIT");
        var metadata=producer().send(new ProducerRecord<>(topic,failureId.toString(),document)).get(settings.waitMs(),TimeUnit.MILLISECONDS);
        return new Ack(metadata.partition(),metadata.offset());
    }
    @Override public synchronized void close() { if(producer!=null) producer.close(Duration.ofSeconds(5)); }
}
