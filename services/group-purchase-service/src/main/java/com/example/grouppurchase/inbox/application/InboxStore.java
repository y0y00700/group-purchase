package com.example.grouppurchase.inbox.application;

/** Called only inside the owning business transaction. True means first committed effect candidate. */
public interface InboxStore {
    boolean register(String role, IncomingRecord record, IncomingMessage message);
}
