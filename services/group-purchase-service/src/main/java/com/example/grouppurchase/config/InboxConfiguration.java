package com.example.grouppurchase.config;

import com.example.grouppurchase.inbox.application.IncomingMessageHandler;
import com.example.grouppurchase.inbox.infrastructure.*;
import com.example.grouppurchase.infrastructure.messaging.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class InboxConfiguration {
    @Bean InboxSettings inboxSettings(Environment e) { return InboxSettings.from(e); }
    @Bean InboxDecoder inboxDecoder(InboxSettings settings,List<IncomingMessageHandler> handlers) {
        var decoder=new InboxDecoder(Map.of(settings.topic(),settings.topic()),handlers);
        if(settings.enabled() && !decoder.registeredTypes().containsAll(Set.of("PaymentApprovalResultReported","PaymentRefundResultReported")))
            throw new IllegalStateException("Enabled inbox requires actual approval and refund result handlers (016)");
        return decoder;
    }
    @Bean InboxProcessor inboxProcessor(JdbcInbox store,InboxDecoder decoder,MeterRegistry metrics) { return new InboxProcessor(store,decoder,metrics); }
    @Bean(initMethod="start",destroyMethod="close") InboxConsumerLoop inboxConsumerLoop(InboxSettings settings,JdbcInbox store,InboxProcessor processor,MeterRegistry metrics) {
        return new InboxConsumerLoop(settings,store,processor,metrics);
    }
    @Bean(destroyMethod="close") DltTransport dltTransport(OutboxSettings settings) { return new KafkaDltTransport(settings,"gp.payment.results.dlt.v1"); }
    @Bean DltPublisher dltPublisher(JdbcDlt store,DltTransport transport) { return new DltPublisher(store,transport,Duration.ofSeconds(60)); }
    @Bean(initMethod="start",destroyMethod="close") DltWorker dltWorker(InboxSettings settings,DltPublisher publisher,JdbcDlt store,MeterRegistry metrics) {
        return new DltWorker(settings,publisher,store,metrics);
    }
    @Bean org.springframework.boot.actuate.info.InfoContributor inboxInfo(InboxConsumerLoop loop,InboxSettings settings) {
        return builder -> builder.withDetail("inbox",Map.of("enabled",settings.enabled(),"status",loop.status()));
    }
    static final class DltWorker implements AutoCloseable,org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
        private final InboxSettings settings; private final DltPublisher publisher;
        private volatile boolean stopping;
        private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"inbox-dlt"));
        DltWorker(InboxSettings settings,DltPublisher publisher,JdbcDlt store,MeterRegistry metrics) {
            this.settings=settings; this.publisher=publisher;
            metrics.gauge("inbox.dlt.pending",store,s->{try{return s.pending();}catch(RuntimeException e){return Double.NaN;}});
            metrics.gauge("inbox.dlt.oldest.seconds",store,s->{try{return s.oldestSeconds();}catch(RuntimeException e){return Double.NaN;}});
        }
        public void start() {
            if(settings.enabled()) executor.scheduleWithFixedDelay(()->{
                if(stopping) return;
                try { publisher.publishOne(); }
                catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("errorCode",e.getClass().getSimpleName()).log("inbox_dlt_round_failed"); }
            },1000,1000,TimeUnit.MILLISECONDS);
        }
        @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) { stopping=true; executor.shutdown(); }
        @Override public void close() {
            stopping=true; executor.shutdown();
            try { if(!executor.awaitTermination(50,TimeUnit.SECONDS)) executor.shutdownNow(); }
            catch(InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
    }
    @Bean org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> inboxStop(InboxConsumerLoop loop) {
        return event -> loop.stop();
    }
}
