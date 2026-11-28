package com.example.pgprobe;

import com.example.pgmock.PgMockApplication;
import com.example.pgmock.application.PgStore;
import com.example.pgmock.domain.*;
import com.example.pgmock.infrastructure.JdbcPgStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.function.Supplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** Test-only transaction barriers. External harness kills this owned child JVM. */
@Configuration(proxyBeanMethods=false)
public class PgCrashProbe {
    public static void main(String[] args) { new SpringApplicationBuilder(PgMockApplication.class,PgCrashProbe.class).run(args); }
    @Bean @Primary PgStore crashStore(JdbcPgStore delegate,@Value("${probe.phase}") String target,@Value("${probe.signal}") String signal,
            @Value("${probe.kind:APPROVAL}") String kind) {
        ThreadLocal<String> touched=new ThreadLocal<>();
        return (PgStore)Proxy.newProxyInstance(PgStore.class.getClassLoader(),new Class<?>[]{PgStore.class},(proxy,method,arguments)-> {
            if(method.getName().equals("transaction")) {
                touched.remove();
                @SuppressWarnings("unchecked") Supplier<Object> action=(Supplier<Object>)arguments[0];
                Object value=delegate.transaction(()-> {
                    Object result=action.get();
                    gate(touched.get()+"-before-commit",target,signal);
                    return result;
                });
                gate(touched.get()+"-after-commit",target,signal);
                touched.remove();
                return value;
            }
            try {
                Object result=method.invoke(delegate,arguments);
                if(method.getName().equals("insert") && result instanceof PgOperation op && op.kind().equals(kind)) touched.set("accept");
                if(method.getName().equals("finish") && result instanceof PgOperation op && op.kind().equals(kind) && !op.status().equals("PENDING")) touched.set("complete");
                return result;
            } catch(InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void gate(String phase,String target,String signal) {
        if(!phase.equals(target))return;
        try {
            Files.writeString(Path.of(signal),phase,StandardOpenOption.CREATE_NEW);
            // Finite safety bound. The harness observes the file then forcibly terminates this JVM.
            Thread.sleep(15000);
            throw new IllegalStateException("Crash harness did not terminate the probe before deadline");
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
