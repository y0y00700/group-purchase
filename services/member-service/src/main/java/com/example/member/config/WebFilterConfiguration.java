package com.example.member.config;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.session.web.http.SessionRepositoryFilter;
import com.example.member.infrastructure.web.*;
@Configuration(proxyBeanMethods=false)
public class WebFilterConfiguration {
    @Bean FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var bean=new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Integer.MIN_VALUE); bean.setAsyncSupported(true);
        bean.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR);
        return bean;
    }
    @Bean FilterRegistrationBean<BoundaryFilter> boundary(@Value("${app.auth.internal-credential}") String secret) {
        if (!secret.matches("[A-Za-z0-9._~-]{32,}")) {
            throw new IllegalArgumentException("Internal credential must contain at least 32 ASCII token characters");
        }
        var bean=new FilterRegistrationBean<>(new BoundaryFilter(secret));
        bean.setOrder(SessionRepositoryFilter.DEFAULT_ORDER-1); bean.setDispatcherTypes(DispatcherType.REQUEST);
        return bean;
    }
}
