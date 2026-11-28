package com.example.pgmock.infrastructure;

import com.example.pgmock.domain.PgOperation;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PgTelemetry {
    private final MeterRegistry registry;
    public PgTelemetry(MeterRegistry registry) { this.registry=registry; }
    public void event(String event,String kind,String status,UUID operationId,int attempt) {
        registry.counter("pg.operations", "event",event,"kind",kind,"status",status).increment();
        LoggerFactory.getLogger(PgTelemetry.class).atInfo().addKeyValue("event","pg_"+event)
            .addKeyValue("kind",kind).addKeyValue("status",status).addKeyValue("operationId",operationId)
            .addKeyValue("attempt",attempt).log("PG operation event");
    }
    public void completed(PgOperation op,String status,long nanos) {
        event("completed",op.kind(),status,op.operationId(),op.attempt());
        registry.timer("pg.execution","kind",op.kind(),"status",status).record(nanos,TimeUnit.NANOSECONDS);
    }
    public void failure(String event,Exception error) {
        registry.counter("pg.failures","event",event).increment();
        LoggerFactory.getLogger(PgTelemetry.class).atWarn().addKeyValue("event",event)
            .addKeyValue("exceptionType",error.getClass().getSimpleName()).log("PG boundary failed");
    }
}
