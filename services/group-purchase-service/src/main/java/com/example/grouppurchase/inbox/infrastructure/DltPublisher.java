package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.infrastructure.messaging.DltTransport;
import com.example.grouppurchase.outbox.infrastructure.DeliveryFailure;
import java.time.Duration;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

public final class DltPublisher {
    private final JdbcDlt store;
    private final DltTransport transport;
    private final Duration lease;
    public DltPublisher(JdbcDlt store,DltTransport transport,Duration lease) { this.store=store; this.transport=transport; this.lease=lease; }
    public boolean publishOne() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("DLT send must be outside DB transaction");
        var candidate=store.claim(lease); if(candidate.isEmpty()) return false;
        var claim=candidate.orElseThrow(); DltTransport.Ack ack;
        try {
            byte[] document=JsonMapper.builder().build().writeValueAsBytes(claim.fields());
            ack=transport.send(claim.failureId(),document);
        } catch(Exception failure) {
            var d=DeliveryFailure.classify(failure);
            long base=Math.min(60000,1000L << Math.min(6,claim.number()-1));
            store.fail(claim,d.retryable(),d.code(),Duration.ofMillis(Math.min(60000,base+java.util.concurrent.ThreadLocalRandom.current().nextLong(base/5+1))));
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return true;
        }
        // A failed DB acknowledgement leaves the lease for retry. Never unpause the source partition.
        boolean recorded=store.complete(claim,ack.partition(),ack.offset());
        org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("failureId",claim.failureId())
                .addKeyValue("claimId",claim.token()).addKeyValue("recorded",recorded).log("inbox_dlt_ack");
        return true;
    }
}
