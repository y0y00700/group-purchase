package com.example.member.interfaces.cli;
import com.example.member.application.ManageLocalMemberService;
import java.util.*;
import java.net.URI;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit local administration only. No account or password in migrations or public APIs. */
@Component
@ConditionalOnProperty(name="app.member-tool.enabled",havingValue="true")
public class LocalMemberTool implements ApplicationRunner {
    private final Environment env;
    private final ManageLocalMemberService manage;
    private final ConfigurableApplicationContext context;
    public LocalMemberTool(Environment env,ManageLocalMemberService manage,ConfigurableApplicationContext context) {
        this.env=env; this.manage=manage; this.context=context;
    }
    public void run(ApplicationArguments args) {
        try {
            String url=env.getRequiredProperty("spring.datasource.url");
            URI uri=URI.create(url.substring("jdbc:".length()));
            if(!env.matchesProfiles("local") || env.matchesProfiles("docker")
                || !Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()) || !"/member".equals(uri.getPath()))
                throw new IllegalArgumentException("Local tool requires local profile and loopback member DB");
            String id=env.getRequiredProperty("app.member-tool.login-id").toLowerCase(Locale.ROOT);
            String status=env.getProperty("app.member-tool.status","ACTIVE");
            var roles=Arrays.stream(env.getProperty("app.member-tool.roles","BUYER").split(",")).distinct().sorted().toList();
            if(!id.matches("[a-z0-9._-]{3,50}") || !Set.of("ACTIVE","DISABLED").contains(status)
                || roles.isEmpty() || !Set.of("BUYER","SELLER","OPERATOR").containsAll(roles))
                throw new IllegalArgumentException("Invalid member tool input");
            boolean update=env.getProperty("app.member-tool.update",Boolean.class,false);
            boolean reset=env.getProperty("app.member-tool.reset-password",Boolean.class,false);
            String password=null;
            if(!update || reset) {
                password=env.getProperty("GP_MEMBER_PASSWORD");
                if(password==null) {
                    if(System.console()==null) throw new IllegalStateException("Use a protected child environment or interactive console");
                    char[] input=System.console().readPassword("Member password: ");
                    password=new String(input); Arrays.fill(input,'\0');
                }
            }
            manage.manage(new ManageLocalMemberService.Command(id,status,roles,password,update,reset));
            System.out.println("Member tool completed for the explicitly selected account.");
        } finally { context.close(); }
    }
}
