package com.example.pgmock;

import com.example.pgmock.application.*;
import com.example.pgmock.domain.*;
import com.example.pgmock.interfaces.PgJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

/** Real PostgreSQL and HTTP, two independent app schedulers, no mocked repository. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgContractTests {
    final String api="testapi_"+UUID.randomUUID().toString().replace("-","");
    final String control="testcontrol_"+UUID.randomUUID().toString().replace("-","");
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    PostgreSQLContainer postgres;
    ConfigurableApplicationContext a,b,disabled;
    JdbcTemplate db;
    int pa,pb,pd;
    record Reply(int status,HttpHeaders headers,tools.jackson.databind.JsonNode json) { }
    ConfigurableApplicationContext start(boolean enabled) {
        var env=new StandardEnvironment(); env.setActiveProfiles("test");
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("isolated",Map.ofEntries(
            Map.entry("server.port","0"),Map.entry("spring.datasource.url",postgres.getJdbcUrl()+"&connectTimeout=1&socketTimeout=3"),
            Map.entry("spring.datasource.username","mock_pg_app"),Map.entry("spring.datasource.password",postgres.getPassword()),
            Map.entry("app.auth.api-credential",api),Map.entry("app.auth.control-credential",enabled?control:""),
            Map.entry("app.mock-control.enabled",String.valueOf(enabled)),Map.entry("app.recovery.delay-ms","50"))));
        return new SpringApplicationBuilder(PgMockApplication.class).environment(env).run();
    }
    int port(ConfigurableApplicationContext context) { return Integer.parseInt(context.getEnvironment().getProperty("local.server.port")); }
    @BeforeAll void setup() {
        String password=UUID.randomUUID().toString();
        postgres=new PostgreSQLContainer("postgres:18.6-bookworm") {
            @Override protected void configure() { super.configure(); getEnvMap().remove("POSTGRES_PASSWORD"); }
        }.withDatabaseName("pg_mock").withUsername("testadmin").withPassword(password)
            .withEnv("POSTGRES_PASSWORD_FILE","/run/secrets/password")
            .withCopyToContainer(Transferable.of(password,0600),"/run/secrets/password");
        postgres.start();
        try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),password);var sql=connection.createStatement()) {
            sql.execute("CREATE ROLE mock_pg_app LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            sql.execute("ALTER DATABASE pg_mock OWNER TO mock_pg_app");
            sql.execute("REVOKE ALL ON DATABASE postgres FROM PUBLIC");
            sql.execute("REVOKE ALL ON DATABASE template1 FROM PUBLIC");
            sql.execute("REVOKE ALL ON DATABASE pg_mock FROM PUBLIC");
        } catch(java.sql.SQLException e) { throw new IllegalStateException(e); }
        a=start(true); b=start(true); disabled=start(false);
        pa=port(a); pb=port(b); pd=port(disabled); db=a.getBean(JdbcTemplate.class);
    }
    @AfterAll void cleanup() { if(disabled!=null)disabled.close(); if(b!=null)b.close(); if(a!=null)a.close(); if(postgres!=null)postgres.stop(); }
    Reply request(int port,String method,String path,String credential,String key,String body,String type) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));
        if(credential!=null) builder.header("Authorization","Bearer "+credential);
        if(key!=null) builder.header("Idempotency-Key",key);
        if(type!=null) builder.header("Content-Type",type);
        var result=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(result.statusCode(),result.headers(),result.body().isEmpty()?null:PgJson.JSON.readTree(result.body()));
    }
    Reply post(UUID id,long amount) throws Exception { return post(pa,"approvals",id,id,amount,"approve:"+id); }
    Reply post(int port,String resource,UUID id,UUID payment,long amount,String key) throws Exception {
        String body="{\"paymentAttemptId\":\""+payment+"\",\"amount\":"+amount+",\"currency\":\"KRW\""+(resource.equals("refunds")?",\"refundId\":\""+id+"\"":"")+"}";
        return request(port,"POST","/pg/v1/"+resource,api,key,body,"application/json");
    }
    Reply get(String resource,UUID id) throws Exception { return request(pa,"GET","/pg/v1/"+resource+"/"+id,api,null,null,null); }
    Reply scenario(String kind,UUID id,long processing,long response,String decision,int failures) throws Exception {
        return request(pa,"PUT","/__admin/v1/scenarios/"+kind+"/"+id,control,null,
            "{\"processingDelayMs\":"+processing+",\"firstResponseDelayMs\":"+response+",\"approvalDecision\":\""+decision+"\",\"transientFailures\":"+failures+"}","application/json");
    }
    int ledger(UUID id) { return db.queryForObject("SELECT count(*) FROM pg_ledger l JOIN pg_operation o USING(operation_id) WHERE payment_attempt_id=?",Integer.class,id); }
    void awaitSuccess(UUID id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<end) { if(get("approvals",id).json.path("status").asString().equals("SUCCEEDED"))return; Thread.sleep(40); }
        fail("Pending recovery deadline exceeded: "+id);
    }
    @Test void normalDuplicateAndStableDatabaseTime() throws Exception {
        UUID id=UUID.randomUUID(); var first=post(id,Long.MAX_VALUE); var again=post(id,Long.MAX_VALUE);
        assertEquals(200,first.status); assertEquals(first.json,again.json); assertEquals(first.json,get("approvals",id).json);
        assertEquals("SUCCEEDED",first.json.path("status").asString()); assertFalse(first.json.path("approvedAt").isNull());
        assertTrue(first.json.path("refundId").isNull()); assertEquals(1,ledger(id));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM pg_operation o JOIN pg_ledger l USING(operation_id) WHERE completed_at<>occurred_at",Integer.class));
    }
    @Test void keyAndBusinessConflicts() throws Exception {
        UUID id=UUID.randomUUID(); post(id,100);
        assertEquals("IDEMPOTENCY_CONFLICT",post(id,101).json.path("code").asString());
        assertEquals("BUSINESS_KEY_CONFLICT",post(pa,"approvals",id,id,100,"different:"+id).json.path("code").asString());
        UUID other=UUID.randomUUID(); assertEquals("IDEMPOTENCY_CONFLICT",post(pa,"approvals",other,other,100,"approve:"+id).json.path("code").asString());
        assertEquals(404,get("approvals",other).status); assertEquals(1,ledger(id));
    }
    @Test void fullRefundOneBusinessAndOriginalApprovalPreserved() throws Exception {
        UUID id=UUID.randomUUID(),refund=UUID.randomUUID(); var original=post(id,300);
        assertEquals("REFUND_AMOUNT_MISMATCH",post(pa,"refunds",refund,id,299,"refund:"+refund).json.path("code").asString());
        var first=post(pa,"refunds",refund,id,300,"refund:"+refund); assertEquals(200,first.status);
        assertEquals("BUSINESS_KEY_CONFLICT",post(pa,"refunds",refund,UUID.randomUUID(),1,"different:"+refund).json.path("code").asString());
        assertEquals("IDEMPOTENCY_CONFLICT",post(pa,"refunds",refund,UUID.randomUUID(),1,"refund:"+refund).json.path("code").asString());
        assertEquals(first.json,post(pb,"refunds",refund,id,300,"refund:"+refund).json);
        UUID other=UUID.randomUUID(); assertEquals("FULL_REFUND_ALREADY_REGISTERED",post(pa,"refunds",other,id,300,"refund:"+other).json.path("code").asString());
        assertEquals(original.json,get("approvals",id).json); assertEquals(2,ledger(id));
    }
    @Test void missingPendingAndDeclinedCannotRefund() throws Exception {
        for(String state:List.of("missing","pending","declined")) {
            UUID id=UUID.randomUUID(),refund=UUID.randomUUID();
            if(!state.equals("missing")) { scenario("APPROVAL",id,state.equals("pending")?30000:0,0,state.equals("declined")?"DECLINE":"APPROVE",0); post(id,100); }
            assertEquals("REFUND_NOT_ALLOWED",post(pa,"refunds",refund,id,100,"refund:"+refund).json.path("code").asString());
        }
    }
    @Test void definitiveDeclineIsStable200WithoutLedger() throws Exception {
        UUID id=UUID.randomUUID(); scenario("APPROVAL",id,0,0,"DECLINE",0); var reply=post(id,100);
        assertEquals(200,reply.status); assertEquals("DECLINED",reply.json.path("status").asString());
        assertEquals(reply.json,post(id,100).json); assertEquals(0,ledger(id));
    }
    @Test void retryBudgetIsPersistentAndGetDoesNotExecute() throws Exception {
        UUID id=UUID.randomUUID(); scenario("APPROVAL",id,0,0,"APPROVE",2);
        assertEquals("PG_TEMPORARILY_UNAVAILABLE",post(id,100).json.path("code").asString());
        for(int i=0;i<3;i++) assertEquals("RETRYABLE_FAILURE",get("approvals",id).json.path("status").asString());
        assertEquals(503,post(id,100).status); assertEquals(200,post(id,100).status); assertEquals(1,ledger(id));
        assertEquals(3,db.queryForObject("SELECT attempt FROM pg_operation WHERE business_id=?",Integer.class,id));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM pg_execution_attempt a JOIN pg_operation o USING(operation_id) WHERE business_id=? AND result='RETRYABLE_FAILURE'",Integer.class,id));
    }
    @Test void refundTemporaryFailureKeepsOneRefundIdentity() throws Exception {
        UUID id=UUID.randomUUID(),r=UUID.randomUUID(); post(id,150); scenario("REFUND",r,0,0,"APPROVE",1);
        assertEquals(503,post(pa,"refunds",r,id,150,"refund:"+r).status);
        UUID other=UUID.randomUUID(); assertEquals("FULL_REFUND_ALREADY_REGISTERED",post(pa,"refunds",other,id,150,"refund:"+other).json.path("code").asString());
        assertEquals(200,post(pb,"refunds",r,id,150,"refund:"+r).status); assertEquals(2,ledger(id));
    }
    @Test void processingDelayReturns202AndSchedulerCompletes() throws Exception {
        UUID id=UUID.randomUUID(); scenario("APPROVAL",id,600,0,"APPROVE",0); var pending=post(id,90);
        assertEquals(202,pending.status); assertEquals("1",pending.headers.firstValue("Retry-After").orElseThrow());
        assertEquals("/pg/v1/approvals/"+id,pending.headers.firstValue("Location").orElseThrow());
        var same=post(id,90); assertEquals(pending.json,same.json); awaitSuccess(id); assertEquals(1,ledger(id));
    }
    @Test void responseDelayHappensAfterCommitAndOnlyOnce() throws Exception {
        UUID id=UUID.randomUUID(); scenario("APPROVAL",id,0,700,"APPROVE",0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting=executor.submit(()->post(id,70));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(ledger(id)==0 && System.nanoTime()<end)Thread.sleep(10);
            assertEquals(1,ledger(id)); assertFalse(waiting.isDone());
            var duplicate=post(pb,"approvals",id,id,70,"approve:"+id); assertEquals(200,duplicate.status);
            assertEquals(duplicate.json,waiting.get(5,TimeUnit.SECONDS).json);
        }
    }
    @Test void scenarioBindingIsImmutableAndIdempotent() throws Exception {
        UUID id=UUID.randomUUID(); scenario("APPROVAL",id,10,0,"APPROVE",0); post(id,100);
        assertEquals(200,scenario("APPROVAL",id,10,0,"APPROVE",0).status);
        assertEquals("SCENARIO_ALREADY_BOUND",scenario("APPROVAL",id,0,0,"DECLINE",0).json.path("code").asString());
        UUID normal=UUID.randomUUID(); post(normal,100);
        assertEquals(409,scenario("APPROVAL",normal,0,0,"DECLINE",0).status);
        assertEquals(400,scenario("REFUND",UUID.randomUUID(),0,0,"DECLINE",0).status);
    }
    @Test void twoAppsConcurrentDuplicatesProduceOneEffect() throws Exception {
        UUID id=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Reply>>();
            for(int i=0;i<12;i++) { int port=i%2==0?pa:pb; futures.add(executor.submit(()->post(port,"approvals",id,id,111,"approve:"+id))); }
            var op=new HashSet<String>(); for(var future:futures) { var r=future.get(15,TimeUnit.SECONDS); assertEquals(200,r.status); op.add(r.json.path("operationId").asString()); }
            assertEquals(1,op.size()); assertEquals(1,ledger(id));
        }
    }
    @Test void concurrentSameKeyDifferentBusinessHasOneWinner() throws Exception {
        UUID left=UUID.randomUUID(),right=UUID.randomUUID(); String key="shared:"+UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var f=executor.submit(()->post(pa,"approvals",left,left,100,key));
            var g=executor.submit(()->post(pb,"approvals",right,right,100,key));
            assertEquals(Set.of(200,409),Set.of(f.get().status,g.get().status)); assertEquals(1,ledger(left)+ledger(right));
        }
    }
    @Test void concurrentDifferentRefundsHaveOneWinner() throws Exception {
        UUID id=UUID.randomUUID(),left=UUID.randomUUID(),right=UUID.randomUUID(); post(id,123);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var f=executor.submit(()->post(pa,"refunds",left,id,123,"refund:"+left));
            var g=executor.submit(()->post(pb,"refunds",right,id,123,"refund:"+right));
            assertEquals(Set.of(200,409),Set.of(f.get().status,g.get().status)); assertEquals(2,ledger(id));
        }
    }
    @Test void controlPutRacingWithAcceptanceHasCoherentSnapshot() throws Exception {
        UUID id=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var f=executor.submit(()->scenario("APPROVAL",id,0,0,"DECLINE",0));
            var g=executor.submit(()->post(id,110)); var put=f.get(); var accepted=g.get();
            assertTrue(put.status==200 || put.status==409);
            assertEquals(put.status==200?"DECLINED":"SUCCEEDED",accepted.json.path("status").asString());
        }
    }
    @Test void authenticationActuatorAndDisabledControlBoundaries() throws Exception {
        assertEquals(401,request(pa,"GET","/pg/v1/approvals/"+UUID.randomUUID(),null,null,null,null).status);
        assertEquals(403,request(pa,"GET","/__admin/v1/transactions?paymentAttemptId="+UUID.randomUUID(),api,null,null,null).status);
        assertEquals(401,request(pa,"GET","/pg/v1/approvals/"+UUID.randomUUID(),control,null,null,null).status);
        assertEquals(404,request(pd,"PUT","/__admin/v1/scenarios/APPROVAL/"+UUID.randomUUID(),null,null,"{}","application/json").status);
        var health=request(pa,"GET","/actuator/health/readiness",null,null,null,null);
        assertEquals(200,health.status); assertEquals(Set.of("status"),health.json.propertyNames());
        assertEquals(401,request(pa,"GET","/actuator/info",null,null,null,null).status);
        assertEquals("014-v1",request(pa,"GET","/actuator/info",api,null,null,null).json.path("configuration").path("revision").asString());
        assertEquals(404,request(pa,"GET","/actuator/env",api,null,null,null).status);
    }
    @Test void strictJsonRejectsAmbiguousAndInvalidAmounts() throws Exception {
        String prefix="{\"paymentAttemptId\":\""+UUID.randomUUID()+"\",\"currency\":\"KRW\",\"amount\":";
        for(String amount:List.of("0","-1","1.0","\"1\"","9223372036854775808","null","true"))
            assertEquals(400,request(pa,"POST","/pg/v1/approvals",api,"approve:strict-json",prefix+amount+"}","application/json").status,amount);
        for(String body:List.of(prefix+"1,\"extra\":1}",prefix+"1,\"amount\":2}",prefix+"1} {}","{}","[]"))
            assertEquals(400,request(pa,"POST","/pg/v1/approvals",api,"approve:strict-json",body,"application/json").status,body);
        assertEquals(400,postInvalidCurrency());
    }
    int postInvalidCurrency() throws Exception {
        return request(pa,"POST","/pg/v1/approvals",api,"approve:currency-test","{\"paymentAttemptId\":\""+UUID.randomUUID()+"\",\"amount\":1,\"currency\":\"USD\"}","application/json").status;
    }
    @Test void bodySizeMediaMethodKeyAndErrorEnvelope() throws Exception {
        assertEquals(413,request(pa,"POST","/pg/v1/approvals",api,"approve:large-body"," ".repeat(16385),"application/json").status);
        assertEquals(415,request(pa,"POST","/pg/v1/approvals",api,"approve:media-test","{}","text/plain").status);
        assertEquals(405,request(pa,"DELETE","/pg/v1/approvals",api,null,null,null).status);
        UUID id=UUID.randomUUID(); assertEquals(400,post(pa,"approvals",id,id,1,"short").status);
        assertEquals(400,post(pa,"approvals",id,id,1,"approve:with space:"+id).status);
        assertThrows(PgFailure.class,()->new PgCommand("APPROVAL",id,id,1,"KRW"," approve:"+id));
        var missing=get("approvals",id); assertEquals(404,missing.status);
        assertEquals("no-store",missing.headers.firstValue("Cache-Control").orElseThrow());
        assertTrue(missing.headers.firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertEquals(missing.headers.firstValue("X-Request-Id").orElseThrow(),missing.json.path("requestId").asString());
        assertEquals(Set.of("type","title","status","detail","instance","code","requestId"),missing.json.propertyNames());
        assertFalse(missing.headers.map().containsKey("set-cookie"));
    }
    @Test void reconciliationHasIndependentBoundedPagesAndNumericTotals() throws Exception {
        UUID id=UUID.randomUUID(),r=UUID.randomUUID(); post(id,Long.MAX_VALUE); post(pa,"refunds",r,id,Long.MAX_VALUE,"refund:"+r);
        var page=request(pa,"GET","/__admin/v1/transactions?paymentAttemptId="+id+"&limit=1",control,null,null,null);
        assertEquals(1,page.json.path("ledger").size()); assertEquals(1,page.json.path("attempts").size()); assertEquals(2,page.json.path("operations").size());
        long cursor=page.json.path("nextLedgerSequence").asLong(); long attempt=page.json.path("nextAttemptSequence").asLong();
        var next=request(pa,"GET","/__admin/v1/transactions?paymentAttemptId="+id+"&limit=1&afterSequence="+cursor+"&afterAttemptSequence="+attempt,control,null,null,null);
        assertEquals(1,next.json.path("ledger").size()); assertEquals(1,next.json.path("attempts").size());
        assertEquals(400,request(pa,"GET","/__admin/v1/transactions?paymentAttemptId="+id+"&limit=101",control,null,null,null).status);
    }
    @Test void acceptanceAndCompletionTransactionsRollbackAsAUnit() {
        PgStore store=a.getBean(PgStore.class); UUID id=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->store.transaction(()-> {
            var scenario=store.lockScenario("APPROVAL",id); store.insert(new PgCommand("APPROVAL",id,id,90,"KRW","approve:"+id),scenario,null);
            throw new IllegalStateException("rollback acceptance");
        }));
        assertNull(store.byBusiness("APPROVAL",id,false));
        store.putScenario("APPROVAL",id,new Scenario(30000,0,"APPROVE",0,null));
        var accepted=a.getBean(AcceptApproval.class).accept(new PgCommand("APPROVAL",id,id,90,"KRW","approve:"+id));
        assertThrows(IllegalStateException.class,()->store.transaction(()-> {
            db.update("UPDATE pg_operation SET ready_at=clock_timestamp() WHERE operation_id=?",accepted.operation().operationId());
            store.finish(store.byId(accepted.operation().operationId(),true)); throw new IllegalStateException("rollback completion");
        }));
        assertEquals(0,ledger(id));
        assertEquals("PENDING",store.byId(accepted.operation().operationId(),false).status());
        db.update("UPDATE pg_operation SET ready_at=clock_timestamp() WHERE operation_id=?",accepted.operation().operationId());
        // Recover explicitly while both schedulers also compete; exactly one committed effect survives.
        a.getBean(CompletePgOperation.class).complete(accepted.operation().operationId()); assertEquals(1,ledger(id));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM pg_execution_attempt WHERE operation_id=?",Integer.class,accepted.operation().operationId()));
    }
    @Test void domainHasNoFrameworkOrTransportDependencies() throws Exception {
        var directory=java.nio.file.Path.of("src/main/java/com/example/pgmock/domain");
        try(var files=java.nio.file.Files.list(directory)) {
            for(var file:files.toList()) {
                String source=java.nio.file.Files.readString(file);
                assertFalse(source.contains("org.springframework")); assertFalse(source.contains("java.sql")); assertFalse(source.contains("jakarta.servlet"));
            }
        }
    }
    @Test void duplicateCredentialsKeysAndRequestIdValidation() throws Exception {
        String path="http://127.0.0.1:"+pa+"/pg/v1/approvals";
        String body="{\"paymentAttemptId\":\""+UUID.randomUUID()+"\",\"amount\":1,\"currency\":\"KRW\"}";
        var duplicateAuth=HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer "+api).header("Authorization","Bearer "+api)
            .header("Idempotency-Key","approve:duplicate-auth").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(401,client.send(duplicateAuth,HttpResponse.BodyHandlers.ofString()).statusCode());
        var duplicateKeys=HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer "+api)
            .header("Idempotency-Key","approve:duplicate-key").header("Idempotency-Key","approve:duplicate-key")
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(400,client.send(duplicateKeys,HttpResponse.BodyHandlers.ofString()).statusCode());
        for(String id:List.of("a".repeat(32),"INVALID_PRIVATE_VALUE")) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pa+"/actuator/health")).header("X-Request-Id",id).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            String selected=response.headers().firstValue("X-Request-Id").orElseThrow();
            assertTrue(selected.matches("[0-9a-f]{32}"));
            if(id.startsWith("a"))assertEquals(id,selected); else assertNotEquals(id,selected);
        }
    }
    @Test void invalidCredentialsFailConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new com.example.pgmock.infrastructure.PgBoundaryFilter("short","",false));
        assertThrows(IllegalArgumentException.class,()->new com.example.pgmock.infrastructure.PgBoundaryFilter(api,api,true));
        assertThrows(IllegalArgumentException.class,()->new com.example.pgmock.infrastructure.PgBoundaryFilter(api,"",true));
        assertDoesNotThrow(()->new com.example.pgmock.infrastructure.PgBoundaryFilter(api,"",false));
    }
    @Test void httpCompletionLogHasRequestIdRouteAndNoDuplicateJsonKeys() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.example.pgmock.infrastructure.PgBoundaryFilter.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            var reply=get("approvals",UUID.randomUUID());
            assertEquals(404,reply.status);
            var event=events.list.stream().filter(e->e.getMessage().equals("PG HTTP completed")).findFirst().orElseThrow();
            assertEquals(reply.headers.firstValue("X-Request-Id").orElseThrow(),event.getMDCPropertyMap().get("requestId"));
            var keys=new HashSet<>(event.getMDCPropertyMap().keySet());
            for(var pair:event.getKeyValuePairs()) assertTrue(keys.add(pair.key),"Duplicate structured JSON key: "+pair.key);
            assertTrue(event.getKeyValuePairs().stream().anyMatch(p->p.key.equals("route") && p.value.toString().contains("{businessId}")));
        } finally { logger.detachAppender(events); events.stop(); }
    }
    @Test void databaseConstraintsProtectKindParentAndCompletionShape() throws Exception {
        UUID id=UUID.randomUUID(); post(id,10);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("UPDATE pg_operation SET kind='REFUND' WHERE business_id=?",id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("UPDATE pg_operation SET completed_at=NULL WHERE business_id=?",id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("UPDATE pg_operation SET amount=0 WHERE business_id=?",id));
        assertEquals(1,ledger(id));
    }
    @Test void boundedParentLockFailureUsesSameIdentityAfterRecovery() throws Exception {
        UUID id=UUID.randomUUID(),r=UUID.randomUUID(); post(id,12);
        try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),"mock_pg_app",postgres.getPassword());var lock=connection.prepareStatement("SELECT operation_id FROM pg_operation WHERE business_id=? FOR UPDATE")) {
            connection.setAutoCommit(false); lock.setObject(1,id); lock.executeQuery().close();
            var failed=post(pb,"refunds",r,id,12,"refund:"+r);
            assertEquals(503,failed.status); assertEquals("PG_UNAVAILABLE",failed.json.path("code").asString());
            assertEquals(404,get("refunds",r).status); connection.rollback();
        }
        assertEquals(200,post(pa,"refunds",r,id,12,"refund:"+r).status); assertEquals(2,ledger(id));
    }
}
