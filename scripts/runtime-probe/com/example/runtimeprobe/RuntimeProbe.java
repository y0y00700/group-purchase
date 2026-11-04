package com.example.runtimeprobe;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

/** Local verification launcher, outside product component scanning and product artifacts. */
public final class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        String role = System.getProperty("probe.role", "service");
        String target = role.equals("gateway") ? "com.example.apigateway.ApiGatewayApplication"
                : "com.example.grouppurchase.GroupPurchaseApplication";
        var refreshed = new AtomicInteger();
        var closed = new AtomicInteger();
        var mainContext = new java.util.concurrent.atomic.AtomicReference<
                org.springframework.context.ConfigurableApplicationContext>();
        var application = new SpringApplication(Class.forName(target));
        application.addInitializers(mainContext::set);
        application.addListeners(event -> {
            if (event instanceof ContextRefreshedEvent refresh
                    && refresh.getApplicationContext() == mainContext.get()) refreshed.incrementAndGet();
            if (event instanceof ContextClosedEvent close
                    && close.getApplicationContext() == mainContext.get()) closed.incrementAndGet();
        });
        var context = application.run(args);
        String identity = UUID.randomUUID().toString();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",
                Integer.parseInt(System.getProperty("probe.port"))), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            int status = 200;
            String body;
            try {
                String path = exchange.getRequestURI().getPath();
                if (!exchange.getRequestMethod().equals("GET")) {
                    status = 405; body = "method rejected";
                } else if (path.equals("/identity")) {
                    body = "pid=" + ProcessHandle.current().pid() + ";processStart="
                            + ProcessHandle.current().info().startInstant().orElseThrow()
                            + ";context=" + identity + ";contextHash=" + System.identityHashCode(context)
                            + ";startup=" + context.getStartupDate() + ";refresh=" + refreshed
                            + ";close=" + closed + ";active=" + context.isActive();
                } else if (path.equals("/sql")) {
                    // A new borrow from the ACTUAL app DataSource, never DriverManager/psql.
                    try (var connection = context.getBean(DataSource.class).getConnection();
                            var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        try (var rows = statement.executeQuery("SELECT current_user, current_database(), "
                                + "current_schema(), pg_backend_pid(), pg_postmaster_start_time()::text")) {
                            if (!rows.next()) throw new IllegalStateException("No SQL row");
                            body = rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3)
                                    + "|" + rows.getInt(4) + "|" + rows.getString(5);
                        }
                    }
                } else if (path.equals("/discovery")) {
                    body = context.getBean(DiscoveryClient.class).getInstances("group-purchase").stream()
                            .map(instance -> instance.getHost() + ":" + instance.getPort()).sorted()
                            .collect(java.util.stream.Collectors.joining(","));
                } else if (path.equals("/supplier") && role.equals("gateway")) {
                    // Read the same per-service supplier chain used by Gateway's load balancer.
                    Class<?> factoryType = Class.forName(
                            "org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory");
                    Class<?> supplierType = Class.forName(
                            "org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier");
                    Object factory = context.getBean(factoryType);
                    Object supplier = factoryType.getMethod("getInstance", String.class, Class.class)
                            .invoke(factory, "group-purchase", supplierType);
                    Object flux = supplierType.getMethod("get").invoke(supplier);
                    var instances = (java.util.List<?>) Class.forName("reactor.core.publisher.Flux")
                            .getMethod("blockFirst", Duration.class).invoke(flux, Duration.ofSeconds(5));
                    body = "count=" + instances.size();
                } else {
                    status = 404; body = "unknown probe";
                }
            } catch (Exception failure) {
                // No JDBC exception text/password/config is exported.
                status = 503; body = "probe failed: " + failure.getClass().getSimpleName();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) server.stop(0);
        });
        server.start();
    }
}
