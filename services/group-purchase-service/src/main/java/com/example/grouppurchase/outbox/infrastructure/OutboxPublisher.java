package com.example.grouppurchase.outbox.infrastructure;

import com.example.grouppurchase.infrastructure.messaging.OutboxTransport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcOutbox store;
    private final OutboxTransport transport;
    private final Duration lease;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopping;
    public OutboxPublisher(JdbcOutbox store, OutboxTransport transport, Duration lease) {
        this.store = store; this.transport = transport; this.lease = lease;
    }

    public boolean publishOne() {
         if (TransactionSynchronizationManager.isActualTransactionActive()) {
             throw new IllegalStateException("Kafka publishing must run outside a DB transaction");
         }
         var candidate = store.claim(lease);
         if (candidate.isEmpty()) return false;
         var claim = candidate.orElseThrow();
         OutboxTransport.Ack ack;
         try {
             ack = transport.send(claim);
         } catch (Exception failure) {
             var classified = DeliveryFailure.classify(failure);
             boolean recorded = store.fail(claim, classified.retryable(), classified.code(), DeliveryFailure.delay(claim.retryCount()));
             LOG.atWarn().addKeyValue("messageId", claim.messageId()).addKeyValue("claimId", claim.claimId())
                     .addKeyValue("paymentAttemptId", claim.paymentAttemptId()).addKeyValue("sequence", claim.sequence())
                     .addKeyValue("errorCode", classified.code()).addKeyValue("recorded", recorded).log("outbox_delivery_failed");
             if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
             return true;
         }
         // A DB failure here must leave the claim for expiry, never become a delivery failure.
         boolean completed = store.complete(claim, ack.partition(), ack.offset());
         LOG.atInfo().addKeyValue("messageId", claim.messageId()).addKeyValue("claimId", claim.claimId())
                 .addKeyValue("recorded", completed).log("outbox_ack_received");
         return true;
    }

    public int poll(int maximum, Duration budget) {
         if (!running.compareAndSet(false, true)) return 0;
         int processed = 0;
         long started = System.nanoTime();
         try {
             while (!stopping && !Thread.currentThread().isInterrupted() && processed < maximum
                     && System.nanoTime() - started < budget.toNanos()) {
                 if (!publishOne()) break;
                 processed++;
             }
         } catch (Exception failure) {
             LOG.atWarn().addKeyValue("errorCode", failure.getClass().getSimpleName()).log("outbox_round_failed");
         } finally { running.set(false); }
         return processed;
    }
    public void stop() { stopping = true; }
}
