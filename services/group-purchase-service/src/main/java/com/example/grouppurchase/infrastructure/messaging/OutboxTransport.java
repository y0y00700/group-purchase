package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.outbox.infrastructure.JdbcOutbox.Claim;

public interface OutboxTransport extends AutoCloseable {
    record Ack(int partition, long offset) { }
    Ack send(Claim message) throws Exception;
    @Override default void close() { }
}
