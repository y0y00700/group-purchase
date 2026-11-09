package com.example.member.config;
import java.util.*;
import com.example.member.application.*;
import com.example.member.application.port.PasswordVerifier;
import com.example.member.domain.member.MemberRepository;
import com.example.member.infrastructure.security.*;
import com.example.member.infrastructure.web.error.Problems;
import com.example.member.interfaces.web.dto.MemberResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.*;

@Configuration(proxyBeanMethods=false)
public class SecurityConfiguration {
    @Bean AuthenticateMemberService authenticateMember(MemberRepository members, PasswordVerifier passwords) { return new AuthenticateMemberService(members,passwords); }
    @Bean GetCurrentMemberService currentMember(MemberRepository members) { return new GetCurrentMemberService(members); }
    @Bean ManageLocalMemberService manageLocalMember(MemberRepository members, PasswordVerifier passwords) { return new ManageLocalMemberService(members,passwords); }
    @Bean(name=org.springframework.web.servlet.DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
    org.springframework.web.servlet.FlashMapManager noFlashSession() {
        return new org.springframework.web.servlet.FlashMapManager() {
            public org.springframework.web.servlet.FlashMap retrieveAndUpdate(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) { return null; }
            public void saveOutputFlashMap(org.springframework.web.servlet.FlashMap flash,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) { throw new IllegalStateException("Flash sessions are disabled"); }
        };
    }
    @Bean org.springframework.session.config.SessionRepositoryCustomizer<org.springframework.session.jdbc.JdbcIndexedSessionRepository> concurrentAttributes() {
        return repository -> repository.setCreateSessionAttributeQuery("""
            INSERT INTO %TABLE_NAME%_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES)
            VALUES (?, ?, ?)
            ON CONFLICT (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
            DO UPDATE SET ATTRIBUTE_BYTES = EXCLUDED.ATTRIBUTE_BYTES
            """);
    }
    // FrameworkServlet's legacy request event eagerly asks for a session even for Actuator.
    @Bean static org.springframework.beans.factory.config.BeanPostProcessor noSessionRequestEvents() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            public Object postProcessBeforeInitialization(Object bean,String name) {
                if(bean instanceof org.springframework.web.servlet.FrameworkServlet servlet) servlet.setPublishEvents(false);
                return bean;
            }
        };
    }
    @Bean static PasswordEncoder encoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository tokens() {
        var tokens=new HttpSessionCsrfTokenRepository(); tokens.setHeaderName("X-CSRF-TOKEN"); return tokens;
    }
    @Bean HeaderCsrfHandler csrfHandler() { return new HeaderCsrfHandler(); }
    @Bean CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie=new DefaultCookieSerializer();
        cookie.setCookieName("GP_SESSION"); cookie.setCookiePath("/"); cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax"); cookie.setUseSecureCookie(secure); return cookie;
    }
    @Bean @Order(1) SecurityFilterChain internal(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/auth/check","/actuator/**");
        http.csrf(c->c.disable()).securityContext(c->c.disable()).requestCache(c->c.disable())
            .anonymous(c->c.disable()).logout(c->c.disable()).sessionManagement(c->c.disable())
            .authorizeHttpRequests(c->c.anyRequest().permitAll());
        return http.build();
    }
    void connectLogin(JsonLoginFilter login,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository tokens) {
        var migration=new SessionFixationProtectionStrategy();
        migration.setMigrateSessionAttributes(false);
        login.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(migration,new CsrfAuthenticationStrategy(tokens))));
        login.setSecurityContextRepository(contexts);
    }
    @Bean @Order(2) SecurityFilterChain publicApi(HttpSecurity http,MemberAuthenticationProvider provider,GetCurrentMemberService current,HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository tokens,HeaderCsrfHandler handler) throws Exception {
        var login=new JsonLoginFilter(new ProviderManager(provider));
        connectLogin(login,contexts,tokens);
        login.setAuthenticationSuccessHandler((request,response,auth)->Problems.json(response,MemberResponse.from(current.get(SessionMemberIdentity.memberId(auth)))));
        login.setAuthenticationFailureHandler((request,response,error)->Problems.write(request,response,401,"AUTHENTICATION_FAILED"));
        http.securityContext(c->c.securityContextRepository(contexts).requireExplicitSave(true))
            .requestCache(c->c.disable())
            .csrf(c->c.csrfTokenRepository(tokens).csrfTokenRequestHandler(handler))
            .authorizeHttpRequests(c->c.anyRequest().permitAll())
            .addFilterAt(login,UsernamePasswordAuthenticationFilter.class)
            .logout(c->c.logoutUrl("/api/auth/logout").logoutSuccessHandler((request,response,auth)->response.setStatus(204)))
            .exceptionHandling(c->c.accessDeniedHandler((request,response,error)->Problems.write(request,response,403,"CSRF_INVALID"))
                .authenticationEntryPoint((request,response,error)->Problems.write(request,response,401,"UNAUTHENTICATED")));
        return http.build();
    }
}
