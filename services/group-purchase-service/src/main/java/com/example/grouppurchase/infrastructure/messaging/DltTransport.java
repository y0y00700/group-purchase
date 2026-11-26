package com.example.grouppurchase.infrastructure.messaging;

import java.util.UUID;

public interface DltTransport extends AutoCloseable {
    record Ack(int partition,long offset) { }
    Ack send(UUID failureId,byte[] document) throws Exception;
    @Override default void close() { }
}
