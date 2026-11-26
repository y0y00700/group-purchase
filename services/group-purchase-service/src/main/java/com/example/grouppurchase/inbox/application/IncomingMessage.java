package com.example.grouppurchase.inbox.application;

import java.util.Map;
import java.util.UUID;

public record IncomingMessage(UUID messageId, String type, UUID businessId, UUID correlationId,
        UUID groupPurchaseId, UUID participationId, Long sequence, Map<String,Object> payload) {
    public IncomingMessage { payload=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
}
