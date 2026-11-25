package com.example.grouppurchase.config;

import com.example.grouppurchase.infrastructure.messaging.KafkaOutboxTransport;
import com.example.grouppurchase.infrastructure.messaging.OutboxTransport;
import com.example.grouppurchase.outbox.infrastructure.JdbcOutbox;
import com.example.grouppurchase.outbox.infrastructure.OutboxPublisher;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
    @Bean OutboxSettings outboxSettings(Environment environment) { return OutboxSettings.from(environment); }
    @Bean(destroyMethod = "close") OutboxTransport outboxTransport(OutboxSettings settings) { return new KafkaOutboxTransport(settings); }
    @Bean OutboxPublisher outboxPublisher(JdbcOutbox store, OutboxTransport transport, OutboxSettings settings) {
        return new OutboxPublisher(store, transport, Duration.ofMillis(settings.leaseMs()));
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    OutboxWorker outboxWorker(OutboxPublisher publisher, OutboxSettings settings, Environment environment) {
        return new OutboxWorker(publisher, settings, environment.getProperty("app.outbox.enabled", Boolean.class, true));
    }
    static final class OutboxWorker implements AutoCloseable, org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
        private final OutboxPublisher publisher;
        private final OutboxSettings settings;
        private final boolean enabled;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "outbox-publisher"); t.setDaemon(false); return t;
        });
        OutboxWorker(OutboxPublisher publisher, OutboxSettings settings, boolean enabled) {
            this.publisher = publisher; this.settings = settings; this.enabled = enabled;
        }
        public void start() {
            if (enabled) executor.scheduleWithFixedDelay(() -> publisher.poll(settings.maximum(), Duration.ofMillis(settings.budgetMs())),
                    settings.pollMs(), settings.pollMs(), TimeUnit.MILLISECONDS);
        }
        @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) {
            publisher.stop(); // Stop new claims at context-close notification, before HTTP graceful shutdown waits.
        }
        @Override public void close() {
            publisher.stop(); executor.shutdown();
            try { if (!executor.awaitTermination(settings.blockMs() + settings.waitMs() + 10000, TimeUnit.MILLISECONDS)) executor.shutdownNow(); }
            catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
    }
}
