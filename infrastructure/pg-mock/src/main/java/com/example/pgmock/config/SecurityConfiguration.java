package com.example.pgmock.config;

import com.example.pgmock.infrastructure.PgBoundaryFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
public class SecurityConfiguration {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noUserLogin() {
        return username->{ throw new org.springframework.security.core.userdetails.UsernameNotFoundException("PG uses machine credentials"); };
    }
    @Bean FilterRegistrationBean<PgBoundaryFilter> boundary(@Value("${app.auth.api-credential}") String api,
            @Value("${app.auth.control-credential:}") String control,@Value("${app.mock-control.enabled:false}") boolean enabled) {
        var bean=new FilterRegistrationBean<>(new PgBoundaryFilter(api,control,enabled)); bean.setOrder(Ordered.HIGHEST_PRECEDENCE); return bean;
    }
    @Bean SecurityFilterChain stateless(HttpSecurity http) throws Exception {
        http.csrf(c->c.ignoringRequestMatchers("/pg/v1/**","/__admin/v1/**"))
            .securityContext(c->c.disable()).requestCache(c->c.disable()).anonymous(c->c.disable()).logout(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.anyRequest().permitAll());
        return http.build();
    }
}
