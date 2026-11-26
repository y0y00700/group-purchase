package com.example.grouppurchase.outbox.infrastructure;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.common.errors.RetriableException;

public record DeliveryFailure(boolean retryable, String code) {
    public static DeliveryFailure classify(Exception failure) {
        Throwable cause = failure;
        while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
        boolean retry = cause instanceof RetriableException || cause instanceof java.util.concurrent.TimeoutException
                || cause instanceof InterruptedException;
        return new DeliveryFailure(retry, cause.getClass().getSimpleName());
    }
    /** Previous persisted retry count, saturating before shifts can overflow. */
    public static Duration delay(long previousRetries) {
        long base = previousRetries >= 6 ? 60000 : 1000L << Math.max(0, previousRetries);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(base * 80 / 100, base + 1));
    }
}
