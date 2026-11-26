package com.example.grouppurchase.config;

import org.springframework.core.env.Environment;

public record OutboxSettings(long pollMs, int maximum, long budgetMs, long leaseMs, String bootstrap,
        long blockMs, int requestMs, int deliveryMs, long waitMs) {
    public OutboxSettings {
        if (pollMs < 1 || pollMs > 60000 || maximum < 1 || maximum > 50 || budgetMs < 1 || budgetMs > 10000
                || leaseMs < 1 || leaseMs > 300000 || bootstrap == null || bootstrap.isBlank()
                || blockMs < 1 || blockMs > 10000 || requestMs < 1 || requestMs > 10000
                || deliveryMs < requestMs || deliveryMs > 30000 || waitMs < deliveryMs || waitMs > 35000
                || blockMs + waitMs + 10000 > leaseMs) throw new IllegalArgumentException("Invalid outbox timing/address settings");
    }
    public static OutboxSettings from(Environment e) {
        return new OutboxSettings(e.getProperty("app.outbox.poll-ms", Long.class, 10000L),
                e.getProperty("app.outbox.maximum", Integer.class, 50), e.getProperty("app.outbox.budget-ms", Long.class, 10000L),
                e.getProperty("app.outbox.lease-ms", Long.class, 60000L), e.getProperty("app.messaging.kafka.bootstrap-servers", "localhost:9092"),
                e.getProperty("app.messaging.kafka.max-block-ms", Long.class, 10000L),
                e.getProperty("app.messaging.kafka.request-timeout-ms", Integer.class, 10000),
                e.getProperty("app.messaging.kafka.delivery-timeout-ms", Integer.class, 30000),
                e.getProperty("app.messaging.kafka.future-wait-ms", Long.class, 35000L));
    }
}
