package com.example.grouppurchase.inbox.application;

/** Synchronous work in the caller's transaction and DB. No REQUIRES_NEW, I/O or async work. */
public interface IncomingMessageHandler {
    String messageType();
    String topic();
    void handle(IncomingMessage message);
}
