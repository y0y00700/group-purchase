package com.example.member;
import com.example.member.infrastructure.web.error.Problems;

import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

/** Real HTTP, PostgreSQL and independent application contexts. No injected mock authentication. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberAuthenticationTests {
    static final String SECRET=UUID.randomUUID().toString()+UUID.randomUUID();
    static final String PASSWORD="SENTINEL_PRIVATE_PASSWORD_007";
    static final UUID ID=UUID.fromString("00700000-0000-0000-0000-000000000001");
    final HttpClient http=HttpClient.newHttpClient();
    PostgreSQLContainer postgres;
    ConfigurableApplicationContext a,b;
    JdbcTemplate db;
    int pa,pb;
    record Reply(int status,HttpHeaders headers,tools.jackson.databind.JsonNode body) {
        String cookie() { return headers.firstValue("Set-Cookie").orElse("").split(";",2)[0]; }
        String token() { return body.path("token").asString(); }
    }
    static class Faults {
        final AtomicReference<String> fail=new AtomicReference<>("");
        final AtomicInteger queries=new AtomicInteger();
        volatile String lastQueryStack;
        volatile CountDownLatch read,release;
        final AtomicBoolean pause=new AtomicBoolean();
    }
    @Configuration(proxyBeanMethods=false)
    static class FaultConfiguration {
        @Bean static Faults faults() { return new Faults(); }
        @Bean static BeanPostProcessor faultDataSource(Faults faults) {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof DataSource delegate)) return bean;
                    return new AbstractDataSource() {
                        public Connection getConnection() throws SQLException { return wrap(delegate.getConnection(),faults); }
                        public Connection getConnection(String u,String p) throws SQLException { return wrap(delegate.getConnection(u,p),faults); }
                    };
                }
            };
        }
        static Connection wrap(Connection connection,Faults faults) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)-> {
                Object result=invoke(connection,method,args);
                if(!method.getName().equals("prepareStatement")) return result;
                String sql=((String)args[0]).toUpperCase(Locale.ROOT);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,values)-> {
                    if(m.getName().startsWith("execute")) {
                        faults.queries.incrementAndGet();
                        faults.lastQueryStack=sql+" "+Arrays.toString(Thread.currentThread().getStackTrace());
                        String fail=faults.fail.get();
                        if((fail.equals("SAVE") && sql.contains("SPRING_SESSION") && (sql.startsWith("INSERT")||sql.startsWith("UPDATE")))
                            || (fail.equals("DELETE") && sql.contains("DELETE FROM SPRING_SESSION")))
                            throw new SQLTransientConnectionException("Injected unavailable connection");
                        Object value=invoke(result,m,values);
                        if(sql.contains("SPRING_SESSION") && sql.startsWith("SELECT") && faults.pause.compareAndSet(true,false)) {
                            faults.read.countDown();
                            if(!faults.release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                        }
                        return value;
                    }
                    return invoke(result,m,values);
                });
            });
        }
        static Object invoke(Object target,Method method,Object[] args) throws Throwable {
            try { return method.invoke(target,args); } catch(InvocationTargetException error) { throw error.getCause(); }
        }
    }
    ConfigurableApplicationContext start() {
        return start("30m");
    }
    ConfigurableApplicationContext start(String timeout) {
        var env=new StandardEnvironment();
        env.setActiveProfiles("local");
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("isolated",Map.ofEntries(
            Map.entry("spring.config.name","member-integration"),Map.entry("server.port","0"),Map.entry("spring.application.name","member"),
            Map.entry("logging.structured.format.console","logstash"),
            Map.entry("spring.cloud.config.enabled","false"),Map.entry("eureka.client.enabled","false"),
            Map.entry("spring.datasource.url",postgres.getJdbcUrl()+"&currentSchema=app&connectTimeout=2&socketTimeout=2"),
            Map.entry("spring.datasource.username","member"),Map.entry("spring.datasource.password",postgres.getPassword()),
            Map.entry("spring.datasource.hikari.connection-timeout","1000"),
            Map.entry("spring.flyway.schemas","app"),Map.entry("spring.flyway.default-schema","app"),
            Map.entry("spring.session.jdbc.initialize-schema","never"),Map.entry("spring.session.jdbc.cleanup-cron","-"),
            Map.entry("spring.session.timeout",timeout),Map.entry("app.auth.internal-credential",SECRET),
            Map.entry("server.servlet.session.cookie.secure","false"),
            Map.entry("app.configuration.service","member"),Map.entry("app.configuration.environment","local"),Map.entry("app.configuration.revision","007-v1"),
            Map.entry("management.endpoints.web.exposure.include","health,info,metrics"),Map.entry("management.endpoint.health.probes.enabled","true"),
            Map.entry("management.endpoint.health.group.readiness.include","readinessState,db"),
            Map.entry("management.endpoint.health.group.liveness.include","livenessState"))));
        return new SpringApplicationBuilder(MemberApplication.class,FaultConfiguration.class).environment(env).run();
    }
    int port(ConfigurableApplicationContext context) { return Integer.parseInt(context.getEnvironment().getProperty("local.server.port")); }
    @BeforeAll void setup() {
        String password=UUID.randomUUID().toString();
        postgres=new PostgreSQLContainer("postgres:18.6-bookworm") {
            @Override protected void configure() { super.configure(); getEnvMap().remove("POSTGRES_PASSWORD"); }
        }.withDatabaseName("member").withUsername("member").withPassword(password)
            .withEnv("POSTGRES_PASSWORD_FILE","/run/secrets/password")
            .withCopyToContainer(Transferable.of(password,0600),"/run/secrets/password");
        postgres.start();
        a=start(); b=start(); pa=port(a); pb=port(b); db=a.getBean(JdbcTemplate.class);
    }
    @AfterAll void cleanup() { if(b!=null)b.close(); if(a!=null)a.close(); if(postgres!=null)postgres.stop(); }
    @BeforeEach void fixture() {
        a.getBean(Faults.class).fail.set(""); b.getBean(Faults.class).fail.set("");
        db.update("DELETE FROM app.spring_session"); db.update("DELETE FROM app.members");
        db.update("INSERT INTO app.members(member_id,login_id,password_hash,status) VALUES (?,?,?,'ACTIVE')",ID,"buyer007",PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        db.update("INSERT INTO app.member_roles VALUES (?, 'BUYER')",ID);
    }
    Reply call(int port,String path,String method,String cookie,String token,String body,boolean internal) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15));
        if(cookie!=null&&!cookie.isEmpty())builder.header("Cookie",cookie);
        if(token!=null)builder.header("X-CSRF-TOKEN",token);
        if(internal)builder.header("X-Internal-Caller","api-gateway").header("X-Internal-Credential",SECRET);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.headers(),response.body().isEmpty()?Problems.JSON.nullNode():Problems.JSON.readTree(response.body()));
    }
    Reply csrf(int port,String cookie) throws Exception { return call(port,"/api/auth/csrf","GET",cookie,null,null,false); }
    Reply login(int port,Reply csrf,String password) throws Exception {
        return call(port,"/api/auth/login","POST",csrf.cookie(),csrf.token(),Problems.JSON.writeValueAsString(Map.of("loginId","BUYER007","password",password)),false);
    }
    String signedIn() throws Exception { var seed=csrf(pa,null); var login=login(pa,seed,PASSWORD); assertEquals(200,login.status()); return login.cookie(); }
    Reply check(String method,String cookie,String token) throws Exception { return call(pb,"/internal/auth/check","POST",cookie,token,"{\"method\":\""+method+"\"}",true); }
    void problem(Reply reply,int status,String code) {
        assertEquals(status,reply.status(),reply.body().toString()); assertEquals(code,reply.body().path("code").asString());
        assertEquals("application/problem+json",reply.headers().firstValue("Content-Type").orElse(""));
        assertTrue(reply.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(reply.body().path("requestId").asString().matches("[a-f0-9]{32}"));
    }
    @Test void shareRotateRestartLogoutAndMinimalPrincipal() throws Exception {
        var seed=csrf(pa,null); var login=login(pb,seed,PASSWORD); assertEquals(200,login.status());
        String cookie=login.cookie(); assertNotEquals(seed.cookie(),cookie);
        problem(call(pa,"/api/members/me","GET",seed.cookie(),null,null,false),401,"UNAUTHENTICATED");
        assertEquals(200,call(pa,"/api/members/me","GET",cookie,null,null,false).status());
        assertEquals(ID.toString(),check("GET",cookie,null).body().path("memberId").asString());
        assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
        assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Lax"));
        String setCookie=login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("Path=/")); assertFalse(setCookie.contains("Domain=")); assertFalse(setCookie.contains("Secure"));
        assertTrue(db.queryForObject("SELECT password_hash FROM app.members WHERE member_id=?",String.class,ID).startsWith("{bcrypt}"));
        for(byte[] value:db.queryForList("SELECT attribute_bytes FROM app.spring_session_attributes",byte[].class)) {
            String raw=new String(value,java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains(PASSWORD)); assertFalse(raw.contains("{bcrypt}")); assertFalse(raw.contains("UserDetails"));
        }
        a.close(); b.close(); a=start(); b=start(); pa=port(a);pb=port(b);db=a.getBean(JdbcTemplate.class);
        assertEquals(200,check("GET",cookie,null).status());
        var fresh=csrf(pa,cookie);
        problem(check("POST",cookie,seed.token()),403,"CSRF_INVALID");
        assertEquals(200,check("PATCH",cookie,fresh.token()).status());
        var logout=call(pb,"/api/auth/logout","POST",cookie,fresh.token(),null,false);
        assertEquals(204,logout.status()); assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        problem(call(pa,"/api/members/me","GET",cookie,null,null,false),401,"UNAUTHENTICATED");
    }
    @Test void credentialInputAndCsrfFailures() throws Exception {
        problem(call(pa,"/api/auth/login","POST",null,null,"{}",false),403,"CSRF_INVALID");
        var seed=csrf(pa,null);
        problem(login(pa,seed,"wrong"),401,"AUTHENTICATION_FAILED");
        db.update("UPDATE app.members SET status='DISABLED'");
        problem(login(pa,seed,PASSWORD),401,"AUTHENTICATION_FAILED");
        db.update("DELETE FROM app.members");
        problem(login(pa,seed,PASSWORD),401,"AUTHENTICATION_FAILED");
        problem(login(pa,seed,"x".repeat(73)),400,"INVALID_REQUEST");
        problem(login(pa,seed,"가".repeat(25)),400,"INVALID_REQUEST");
        problem(call(pa,"/api/auth/login","POST",seed.cookie(),seed.token(),"{",false),400,"INVALID_REQUEST");
        var other=csrf(pb,null);
        problem(call(pa,"/api/auth/logout","POST",seed.cookie(),other.token(),null,false),403,"CSRF_INVALID");
    }
    @Test void currentRolesStatusAndInternalOrder() throws Exception {
        String cookie=signedIn();
        db.update("INSERT INTO app.member_roles VALUES (?, 'SELLER')",ID);
        assertTrue(check("GET",cookie,null).body().path("roles").toString().contains("SELLER"));
        problem(check("DELETE",cookie,null),403,"CSRF_INVALID");
        problem(check("TRACE",cookie,null),400,"INVALID_REQUEST");
        problem(check("POST",null,null),401,"UNAUTHENTICATED");
        db.update("UPDATE app.members SET status='DISABLED'");
        problem(check("POST",cookie,null),401,"UNAUTHENTICATED");
        var token=csrf(pa,cookie);
        assertEquals(204,call(pb,"/api/auth/logout","POST",cookie,token.token(),null,false).status());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM app.spring_session",Integer.class));
        problem(check("GET",null,null),401,"UNAUTHENTICATED");
        assertEquals(0,db.queryForObject("SELECT count(*) FROM app.spring_session",Integer.class));
    }
    @Test void routeCallerCookieAndActuatorDoNotReadSession() throws Exception {
        var faults=a.getBean(Faults.class); faults.queries.set(0);
        problem(call(pa,"/internal/auth/check","POST","GP_SESSION=broken",null,"{}",false),403,"INTERNAL_CALLER_DENIED");
        problem(call(pa,"/api/members/me","GET","GP_SESSION=a; GP_SESSION=b",null,null,false),400,"INVALID_REQUEST");
        problem(call(pa,"/api/members/me","POST",null,null,null,false),405,"METHOD_NOT_ALLOWED");
        problem(call(pa,"/login","GET",null,null,null,false),404,"NOT_FOUND");
        assertEquals(200,call(pa,"/actuator/info","GET","GP_SESSION=broken",null,null,false).status());
        assertEquals(0,faults.queries.get(),faults.lastQueryStack);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pa+"/internal/auth/check"))
            .header("X-Internal-Caller","api-gateway").header("X-Internal-Caller","api-gateway")
            .header("X-Internal-Credential",SECRET).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0,faults.queries.get());
        for(boolean duplicateCredential:List.of(false,true)) {
            var denied=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pa+"/internal/auth/check"))
                .header("X-Internal-Caller","api-gateway").header("X-Internal-Credential",duplicateCredential?SECRET:"wrong");
            if(duplicateCredential)denied.header("X-Internal-Credential",SECRET);
            assertEquals(403,http.send(denied.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(0,faults.queries.get());
    }
    @Test void logicalExpiryBeforeCleanupAndAuthorityRemoval() throws Exception {
        String cookie;
        try(var shortLived=start("2s")) {
            int port=port(shortLived);
            var seed=csrf(port,null); var loggedIn=login(port,seed,PASSWORD);
            assertEquals(200,loggedIn.status()); cookie=loggedIn.cookie();
            Thread.sleep(2200);
            problem(check("GET",cookie,null),401,"UNAUTHENTICATED");
        }
        cookie=signedIn(); db.update("DELETE FROM app.member_roles");
        problem(check("GET",cookie,null),401,"UNAUTHENTICATED");
        db.update("DELETE FROM app.members");
        problem(check("GET",cookie,null),401,"UNAUTHENTICATED");
    }
    @Test void finalSaveAndDeleteFailuresDiscardSuccessAndCookies() throws Exception {
        var seed=csrf(pa,null); var faults=a.getBean(Faults.class);
        faults.fail.set("SAVE");
        var failed=login(pa,seed,PASSWORD); problem(failed,503,"AUTHENTICATION_UNAVAILABLE");
        assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
        assertFalse(failed.body().has("memberId"));
        faults.fail.set(""); String cookie=signedIn(); var fresh=csrf(pa,cookie);
        faults.fail.set("DELETE");
        failed=call(pa,"/api/auth/logout","POST",cookie,fresh.token(),null,false);
        problem(failed,503,"AUTHENTICATION_UNAVAILABLE"); assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
        faults.fail.set(""); assertEquals(200,check("GET",cookie,null).status());
        faults.fail.set("SAVE");
        failed=csrf(pa,null); problem(failed,503,"AUTHENTICATION_UNAVAILABLE");
        assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
        faults.fail.set("");
    }
    @Test void delayedOldReadCannotResurrectRotatedOrLoggedOutSession() throws Exception {
        for(boolean logout:List.of(false,true)) {
            var seed=csrf(pb,null); String cookie=logout?signedIn():seed.cookie();
            var token=logout?csrf(pb,cookie):seed;
            var faults=a.getBean(Faults.class);
            faults.read=new CountDownLatch(1);faults.release=new CountDownLatch(1);faults.pause.set(true);
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var old=executor.submit(()->call(pa,"/api/members/me","GET",cookie,null,null,false));
                assertTrue(faults.read.await(10,TimeUnit.SECONDS));
                try {
                    if(logout) assertEquals(204,call(pb,"/api/auth/logout","POST",cookie,token.token(),null,false).status());
                    else assertEquals(200,login(pb,seed,PASSWORD).status());
                } finally { faults.release.countDown(); }
                old.get(15,TimeUnit.SECONDS);
            }
            problem(check("GET",cookie,null),401,"UNAUTHENTICATED");
        }
    }
    @Test void databaseOutageCallerPrecheckProbesAndSameContextRecovery() throws Exception {
        String cookie=signedIn();
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            problem(call(pa,"/internal/auth/check","POST",cookie,null,"{}",false),403,"INTERNAL_CALLER_DENIED");
            problem(check("GET",cookie,null),503,"AUTHENTICATION_UNAVAILABLE");
            assertEquals(200,call(pa,"/actuator/health/liveness","GET",null,null,null,false).status());
            assertEquals(503,call(pa,"/actuator/health/readiness","GET",null,null,null,false).status());
        } finally { postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec(); }
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(check("GET",cookie,null).status()!=200) { assertTrue(System.nanoTime()<end); Thread.sleep(250); }
    }
    @Test void concurrentFirstTokenSavesDoNotBreakSession() throws Exception {
        String cookie=signedIn();
        var faults=a.getBean(Faults.class);
        faults.read=new CountDownLatch(1); faults.release=new CountDownLatch(1); faults.pause.set(true);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var delayed=executor.submit(()->csrf(pa,cookie));
            assertTrue(faults.read.await(10,TimeUnit.SECONDS));
            try { assertEquals(200,csrf(pb,cookie).status()); }
            finally { faults.release.countDown(); }
            assertEquals(200,delayed.get(15,TimeUnit.SECONDS).status());
        }
        assertEquals(200,check("POST",cookie,csrf(pb,cookie).token()).status());
    }
    @Test void internalInputBeforeDatabaseAndHeaderOnlyCsrf() throws Exception {
        var faults=b.getBean(Faults.class); faults.queries.set(0);
        for(String method:List.of("TRACE","CONNECT","get","UNKNOWN",""))
            problem(check(method,"GP_SESSION=YmFk",null),400,"INVALID_REQUEST");
        problem(call(pb,"/internal/auth/check","POST",null,null,"{",true),400,"INVALID_REQUEST");
        assertEquals(0,faults.queries.get());
        String cookie=signedIn(); var token=csrf(pa,cookie);
        problem(check("POST",cookie,csrf(pb,null).token()),403,"CSRF_INVALID");
        for(String method:List.of("GET","HEAD","OPTIONS")) assertEquals(200,check(method,cookie,null).status());
        for(String method:List.of("POST","PUT","PATCH","DELETE")) assertEquals(200,check(method,cookie,token.token()).status());
        problem(call(pa,"/api/auth/logout?_csrf="+token.token(),"POST",cookie,null,null,false),403,"CSRF_INVALID");
        problem(call(pa,"/api/members/me","GET",null,null,null,false),401,"UNAUTHENTICATED");
        var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pb+"/internal/auth/check"))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("X-Internal-Caller","api-gateway").header("X-Internal-Credential",SECRET)
            .header("X-CSRF-TOKEN",token.token()).header("X-CSRF-TOKEN",token.token())
            .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"POST\"}")).build();
        assertEquals(403,http.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode());
        var unsupported=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pb+"/internal/auth/check"))
            .header("Cookie",cookie).header("Content-Type","text/plain").header("X-Internal-Caller","api-gateway")
            .header("X-Internal-Credential",SECRET).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415,http.send(unsupported,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void schemaAndSerializationErrorsRemain500() throws Exception {
        String cookie=signedIn();
        db.update("UPDATE app.spring_session_attributes SET attribute_bytes=?",new byte[]{1,2,3});
        problem(check("GET",cookie,null),500,"INTERNAL_ERROR");
        db.execute("ALTER TABLE app.spring_session RENAME TO broken_session_schema");
        try {
            var failed=csrf(pa,null); problem(failed,500,"INTERNAL_ERROR");
            assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
        } finally { db.execute("ALTER TABLE app.broken_session_schema RENAME TO spring_session"); }
    }
    @Test void databaseRejectsUnknownRolesStatusesAndUnnormalizedIds() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->db.update("INSERT INTO app.member_roles VALUES (?, 'ADMIN')",ID));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->db.update("UPDATE app.members SET status='UNKNOWN' WHERE member_id=?",ID));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->db.update("UPDATE app.members SET login_id='UPPER' WHERE member_id=?",ID));
    }
    @Test void existingSerializedPrincipalSurvivesPackageRestructure() throws Exception {
        verifyLegacyPrincipalDescriptor();
        // A real pre-DDD product JAR creates the session in the same isolated test DB.
        var jar=java.nio.file.Path.of(System.getProperty("test.legacy-member-jar"));
        if(!java.nio.file.Files.isRegularFile(jar)) {
            // The committed old-JAR serialization fixture and real HTTP checks above always run.
            // A locally preserved pre-DDD JAR additionally exercises the original login process.
            return;
        }
        int oldPort;
        try(var socket=new java.net.ServerSocket(0)) { oldPort=socket.getLocalPort(); }
        var log=java.nio.file.Files.createTempFile("007-legacy-member-",".jsonl");
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var builder=new ProcessBuilder(executable,"-jar",jar.toString());
        var environment=builder.environment();
        environment.keySet().removeIf(key->key.startsWith("SPRING_")||key.startsWith("APP_")||key.startsWith("EUREKA_")||key.startsWith("GP_")||key.equals("CONFIG_SERVER_URL"));
        environment.putAll(Map.ofEntries(
            Map.entry("SPRING_CONFIG_NAME","member-integration"),Map.entry("SPRING_PROFILES_ACTIVE","local"),
            Map.entry("SPRING_APPLICATION_NAME","member"),Map.entry("SPRING_CLOUD_CONFIG_ENABLED","false"),
            Map.entry("EUREKA_CLIENT_ENABLED","false"),Map.entry("SERVER_PORT",Integer.toString(oldPort)),
            Map.entry("SPRING_DATASOURCE_URL",postgres.getJdbcUrl()+"&currentSchema=app&connectTimeout=2&socketTimeout=2"),
            Map.entry("SPRING_DATASOURCE_USERNAME","member"),Map.entry("SPRING_DATASOURCE_PASSWORD",postgres.getPassword()),
            Map.entry("SPRING_FLYWAY_SCHEMAS","app"),Map.entry("SPRING_FLYWAY_DEFAULT_SCHEMA","app"),
            Map.entry("SPRING_SESSION_JDBC_INITIALIZE_SCHEMA","never"),Map.entry("SPRING_SESSION_JDBC_CLEANUP_CRON","-"),
            Map.entry("SERVER_SERVLET_SESSION_COOKIE_SECURE","false"),Map.entry("APP_AUTH_INTERNAL_CREDENTIAL",SECRET),
            Map.entry("APP_CONFIGURATION_SERVICE","member"),Map.entry("APP_CONFIGURATION_ENVIRONMENT","local"),Map.entry("APP_CONFIGURATION_REVISION","007-v1")));
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(true) {
                try { if(call(oldPort,"/actuator/health","GET",null,null,null,false).status()==200) break; }
                catch(java.io.IOException ignored) { }
                assertTrue(process.isAlive(),"Legacy JAR stopped before health");
                assertTrue(System.nanoTime()<end,"Legacy JAR health timed out");Thread.sleep(250);
            }
            var seed=csrf(oldPort,null);var login=login(oldPort,seed,PASSWORD);
            assertEquals(200,login.status());String cookie=login.cookie();
            assertEquals(200,call(pa,"/api/members/me","GET",cookie,null,null,false).status());
            assertEquals(200,check("GET",cookie,null).status());
            a.close();b.close();a=start();b=start();pa=port(a);pb=port(b);db=a.getBean(JdbcTemplate.class);
            assertEquals(200,check("GET",cookie,null).status());
        } finally {
            process.destroy();if(!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS); }
            String raw=java.nio.file.Files.readString(log);
            java.nio.file.Files.delete(log);
            assertFalse(raw.contains(PASSWORD));assertFalse(raw.contains(SECRET));
        }
    }
    private void verifyLegacyPrincipalDescriptor() throws Exception {
        assertEquals("com.example.member.Members$Identity",Members.Identity.class.getName());
        assertEquals(1L,java.io.ObjectStreamClass.lookup(Members.Identity.class).getSerialVersionUID());
        byte[] bytes;
        try(var source=getClass().getResourceAsStream("/legacy-member-identity.base64")) {
            assertNotNull(source);
            bytes=Base64.getDecoder().decode(new String(source.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).trim());
        }
        Members.Identity principal;
        try(var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            principal=(Members.Identity)input.readObject();
        }
        assertEquals(ID,principal.memberId());
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUYER"))));
        @SuppressWarnings("unchecked")
        org.springframework.session.SessionRepository<org.springframework.session.Session> repository=
            (org.springframework.session.SessionRepository<org.springframework.session.Session>)
            (org.springframework.session.SessionRepository<?>)a.getBean(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class);
        var session=repository.createSession();
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        repository.save(session);
        String cookie="GP_SESSION="+Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200,call(pa,"/api/members/me","GET",cookie,null,null,false).status());
        assertEquals(200,check("GET",cookie,null).status());
    }
}
