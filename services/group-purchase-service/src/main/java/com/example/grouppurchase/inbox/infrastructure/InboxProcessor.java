package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.inbox.application.*;
import com.example.grouppurchase.infrastructure.messaging.InboxDecoder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class InboxProcessor {
    public record Result(boolean completed, long next, String state) { }
    private record Execution(Result result,boolean fresh) { }
    public static final class DatabaseUncertain extends RuntimeException {
        public final JdbcInbox.Partition owner; public final JdbcInbox.Attempt attempt;
        DatabaseUncertain(JdbcInbox.Partition owner,JdbcInbox.Attempt attempt,Throwable cause) { super(cause); this.owner=owner; this.attempt=attempt; }
    }
    private final JdbcInbox store;
    private final InboxDecoder decoder;
    private final MeterRegistry metrics;
    public InboxProcessor(JdbcInbox store,InboxDecoder decoder,MeterRegistry metrics) { this.store=store; this.decoder=decoder; this.metrics=metrics; }

    public Result process(JdbcInbox.Partition owner, IncomingRecord record) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Inbox entry must be outside transaction");
        IncomingMessage message=null; RuntimeException invalid=null;
        try { message=decoder.decode(record); } catch(RuntimeException e) { invalid=e; }
        JdbcInbox.Attempt attempt;
        try { attempt=store.begin(owner,record,message); }
        catch(JdbcInbox.OwnershipLost | JdbcInbox.PartitionHeld e) { throw e; }
        catch(RuntimeException e) { throw new DatabaseUncertain(owner,null,e); }
        try {
            if(invalid!=null) throw invalid;
            if(attempt.id()==null) throw new MessageRejected("RETRY_EXHAUSTED");
            return execute(owner,record,message,attempt);
        } catch(JdbcInbox.OwnershipLost | JdbcInbox.PartitionHeld e) { throw e; }
        catch(RuntimeException failure) {
            var decision=ConsumptionFailurePolicy.classify(failure);
            if(decision.kind()==ConsumptionFailurePolicy.Kind.DATABASE_UNAVAILABLE) {
                try { store.commonFailure(owner,attempt,decision.code()); } catch(RuntimeException unavailable) { /* reconciled before next attempt */ }
                throw new DatabaseUncertain(owner,attempt,failure);
            }
            java.util.UUID id;
            try { id=store.fail(owner,record,message,attempt,decision); }
            catch(RuntimeException unavailable) { throw new DatabaseUncertain(owner,attempt,unavailable); }
            metrics.counter("inbox.failures","classification",decision.kind().name()).increment();
            var p=store.read(owner.key()).orElseThrow();
            if(p.state().equals("RETRY_WAIT")) metrics.counter("inbox.retries").increment();
            LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("group",owner.key().group()).addKeyValue("role",owner.key().role())
                    .addKeyValue("topic",record.topic()).addKeyValue("partition",record.partition()).addKeyValue("offset",record.offset())
                    .addKeyValue("failureId",id).addKeyValue("attempt",attempt.charged()).addKeyValue("ownerToken",owner.token())
                    .addKeyValue("errorCode",decision.code()).addKeyValue("state",p.state()).log("inbox_failure_persisted");
            return new Result(false,p.next(),p.state());
        }
    }
    private Result execute(JdbcInbox.Partition owner,IncomingRecord record,IncomingMessage message,JdbcInbox.Attempt attempt) {
        var result=store.transaction(() -> {
            var p=store.lock(owner); store.eligible(p,record);
            if(record.offset()<p.next() && !store.completed(p.key().role(),record,message)) throw new MessageRejected("OFFSET_GAP");
            boolean fresh=store.register(p.key().role(),record,message);
            if(fresh) decoder.handle(message);
            long next=store.succeed(p,record,attempt);
            return new Execution(new Result(true,next,"ACTIVE"),fresh);
        });
        metrics.counter("inbox.processed","result",result.fresh()?"new":"duplicate").increment();
        LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("group",owner.key().group()).addKeyValue("role",owner.key().role())
                .addKeyValue("topic",record.topic()).addKeyValue("partition",record.partition()).addKeyValue("offset",record.offset())
                .addKeyValue("messageId",message.messageId()).addKeyValue("correlationId",message.correlationId()).addKeyValue("businessId",message.businessId())
                .addKeyValue("ownerToken",owner.token()).addKeyValue("duplicate",!result.fresh()).log("inbox_db_completed");
        return result.result();
    }
}
