package com.example.pgmock.config;

import com.example.pgmock.application.*;
import com.example.pgmock.infrastructure.PgTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods=false)
public class PgConfiguration {
    @Bean RetryPgOperation retry(PgStore store) { return new RetryPgOperation(store); }
    @Bean AcceptApproval approval(PgStore store,RetryPgOperation retry) { return new AcceptApproval(store,retry); }
    @Bean AcceptRefund refund(PgStore store,RetryPgOperation retry) { return new AcceptRefund(store,retry); }
    @Bean CompletePgOperation complete(PgStore store) { return new CompletePgOperation(store); }
    @Bean RecoverPendingOperations recover(PgStore store,CompletePgOperation complete,PgTelemetry telemetry) {
        return new RecoverPendingOperations(store,complete,e->telemetry.failure("recovery_failed",e));
    }
    @Bean Object pendingGauges(PgStore store,MeterRegistry registry) {
        for(String status:java.util.List.of("PENDING","RETRYABLE_FAILURE"))
            registry.gauge("pg.current",io.micrometer.core.instrument.Tags.of("status",status),store,s->safe(()->s.count(status)));
        registry.gauge("pg.oldest.pending.seconds",store,s->safe(s::oldestPendingSeconds));
        return new Object();
    }
    private static double safe(java.util.function.DoubleSupplier action) { try { return action.getAsDouble(); } catch(RuntimeException e) { return Double.NaN; } }
    @Bean InfoContributor identity(@Value("${app.configuration.environment}") String environment) {
        return builder->builder.withDetail("configuration",java.util.Map.of("service","pg-mock","environment",environment,"revision","014-v1"));
    }
    @Bean Scheduler scheduler(RecoverPendingOperations recover) { return new Scheduler(recover); }
    public record Scheduler(RecoverPendingOperations recovery) {
        @Scheduled(fixedDelayString="${app.recovery.delay-ms:500}",initialDelayString="${app.recovery.initial-delay-ms:500}")
        public void tick() { recovery.recover(); }
    }
}
