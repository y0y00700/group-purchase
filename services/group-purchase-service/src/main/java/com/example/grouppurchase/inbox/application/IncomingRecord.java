package com.example.grouppurchase.inbox.application;

import java.util.List;

/** Lossless transport input; no Kafka/Jackson types cross this port. */
public record IncomingRecord(String topic, String topicId, int partition, long offset, byte[] key, byte[] value,
        List<Header> headers, long timestamp, String timestampType, Integer leaderEpoch) {
    public IncomingRecord { key=copy(key); value=copy(value); headers=List.copyOf(headers); }
    @Override public byte[] key() { return copy(key); }
    @Override public byte[] value() { return copy(value); }
    public record Header(String name, byte[] value) {
        public Header { value=copy(value); }
        @Override public byte[] value() { return copy(value); }
    }
    private static byte[] copy(byte[] b) { return b==null ? null : b.clone(); }
}
