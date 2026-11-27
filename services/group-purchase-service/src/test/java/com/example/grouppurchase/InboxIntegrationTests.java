package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.*;
import com.example.grouppurchase.config.*;
import com.example.grouppurchase.inbox.application.*;
import com.example.grouppurchase.inbox.infrastructure.*;
import com.example.grouppurchase.inboxprobe.ProbeSupport;
import com.example.grouppurchase.infrastructure.messaging.*;
import com.example.grouppurchase.outbox.infrastructure.JdbcOutbox;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage.*;
import com.example.grouppurchase.participation.application.ChangeParticipation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Timeout(240)
class InboxIntegrationTests {
    static PostgresFixture db;
    static ConfigClientIntegrationTests.ConfigServerFixture config;
    static ConfigurableApplicationContext app;
    static KafkaContainer broker;
    static JdbcTemplate sql;
    static JdbcInbox inbox;
    static JdbcDlt dlt;
    static JdbcOutbox outbox;
    static final String TOPIC="verify.inbox.unit",GROUP="verify.inbox.unit.group",ROLE="verify.inbox.role",ID="unit-topic-id";
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    static ConfigurableApplicationContext start(String... extra) {
        var args=new ArrayList<>(List.of("--spring.profiles.active=local","--CONFIG_SERVER_URL="+config.url(),"--spring.cloud.config.enabled=true","--eureka.client.enabled=false","--server.port=0","--app.outbox.enabled=false"));
        args.addAll(List.of(extra)); return db.configure(new SpringApplicationBuilder(GroupPurchaseApplication.class),"local").run(args.toArray(String[]::new));
    }
    @BeforeAll static void setup() throws Exception {
        db=new PostgresFixture(); config=new ConfigClientIntegrationTests.ConfigServerFixture(Path.of(System.getProperty("test.config-repo")));
        try(var v4=start("--spring.flyway.target=4")) {
            assertThat(v4.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success AND version IS NOT NULL",Long.class)).isEqualTo(4);
        }
        app=start(); sql=app.getBean(JdbcTemplate.class); inbox=app.getBean(JdbcInbox.class); dlt=app.getBean(JdbcDlt.class); outbox=app.getBean(JdbcOutbox.class);
        ProbeSupport.schema(sql);
        broker=new KafkaContainer("apache/kafka:4.1.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"); broker.start();
    }
    @AfterAll static void cleanup() throws Exception {
        try { if(broker!=null) broker.stop(); if(app!=null) app.close(); }
        finally { try { if(config!=null) config.close(); } finally { if(db!=null) db.close(); } }
    }
    @BeforeEach void clear() {
        sql.execute("TRUNCATE app.inbox_partition_states,app.inbox_failures,app.inbox_attempts,app.inbox_processed_messages,app.outbox_delivery_attempts,app.outbox_messages,app.outbox_streams,app.inbox_probe_business");
    }
    JdbcInbox.Partition owner(int p) { return inbox.restorePartition(new JdbcInbox.Key(GROUP,TOPIC,p,ROLE),new JdbcInbox.Range(ID,3,0,100,null),true,ID,3).partition(); }
    IncomingRecord raw(int p,long offset,UUID business,byte[] value) { return new IncomingRecord(TOPIC,ID,p,offset,business.toString().getBytes(StandardCharsets.UTF_8),value,List.of(new IncomingRecord.Header("duplicate",null),new IncomingRecord.Header("duplicate",new byte[]{0,-1})),1234,"CREATE_TIME",7); }
    byte[] sample(UUID id,UUID business,Map<String,Object> extra) { return ProbeSupport.sample(id,business,"SUCCESS",100,extra); }
    IncomingMessageHandler handler() { return ProbeSupport.handler(sql,outbox,GROUP,null); }
    InboxProcessor processor(IncomingMessageHandler h) { return new InboxProcessor(inbox,new InboxDecoder(Map.of(TOPIC,ProbeSupport.CANONICAL),List.of(h)),metrics); }
    InboxProcessor processor() { return processor(handler()); }
    long count(String table) { return sql.queryForObject("SELECT count(*) FROM app."+table,Long.class); }
    void due() { sql.update("UPDATE app.inbox_partition_states SET retry_at=clock_timestamp()-interval '1s' WHERE state='RETRY_WAIT'"); }
    void assertEmptyEffects() { assertThat(count("inbox_processed_messages")).isZero(); assertThat(count("inbox_probe_business")).isZero(); assertThat(count("outbox_messages")).isZero(); }
    IncomingMessageHandler wrap(java.util.function.Consumer<IncomingMessage> action) {
        return new IncomingMessageHandler() {
            public String messageType(){return ProbeSupport.TYPE;} public String topic(){return ProbeSupport.CANONICAL;} public void handle(IncomingMessage m){action.accept(m);}
        };
    }
    @Test void migrationAndProductDisabledAndMissingHandlersFailStartup() {
        assertThat(sql.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success AND version IS NOT NULL",Long.class)).isEqualTo(5);
        assertThat(app.getBean(InboxConsumerLoop.class).status()).isEqualTo("DISABLED");
        assertThatThrownBy(()->start("--app.inbox.enabled=true","--app.inbox.expected-topic-id=expected"))
                .hasStackTraceContaining("requires actual approval and refund result handlers");
        assertThatThrownBy(()->new InboxDecoder(Map.of(TOPIC,ProbeSupport.CANONICAL),List.of(handler(),handler()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateAtNewOffsetAndBusinessTransitionsAreIndependentOfSequence() {
        var p=owner(0); var business=UUID.randomUUID(); var id=UUID.randomUUID(); byte[] bytes=ProbeSupport.sample(id,business,"PENDING",100,Map.of());
        assertThat(processor().process(p,raw(0,0,business,bytes)).completed()).isTrue();
        assertThat(processor().process(p,raw(0,2,business,bytes)).next()).isEqualTo(3); // natural offset gap
        processor().process(p,raw(0,3,business,ProbeSupport.sample(UUID.randomUUID(),business,"PENDING",100,Map.of())));
        processor().process(p,raw(0,4,business,ProbeSupport.sample(UUID.randomUUID(),business,"SUCCESS",100,Map.of())));
        processor().process(p,raw(0,5,business,ProbeSupport.sample(UUID.randomUUID(),business,"PENDING",100,Map.of())));
        assertThat(count("inbox_processed_messages")).isEqualTo(4); assertThat(count("outbox_messages")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT effect_count FROM app.inbox_probe_business",Integer.class)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT business_state FROM app.inbox_probe_business",String.class)).isEqualTo("SUCCESS");
    }
    @Test void concurrentSameMessageAndDifferentMessageSameBusinessHaveOneEffect() throws Exception {
        var a=owner(0); var b=owner(1); var business=UUID.randomUUID(); byte[] bytes=sample(UUID.randomUUID(),business,Map.of());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var x=pool.submit(()->{start.await(); return processor().process(a,raw(0,0,business,bytes));});
            var y=pool.submit(()->{start.await(); return processor().process(b,raw(1,0,business,bytes));});
            start.countDown(); assertThat(x.get(20,TimeUnit.SECONDS).completed()).isTrue(); assertThat(y.get(20,TimeUnit.SECONDS).completed()).isTrue();
        }
        processor().process(a,raw(0,1,business,sample(UUID.randomUUID(),business,Map.of())));
        assertThat(count("inbox_processed_messages")).isEqualTo(2); assertThat(count("outbox_messages")).isEqualTo(1);
    }
    @Test void registrationBusinessAndOutboxFailuresRollbackTogetherAndRetryCanRecover() {
        for(String phase:List.of("afterBusiness","largeOutbox","afterOutbox")) {
            clear(); var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of(phase,true)));
            assertThat(processor().process(p,record).state()).isEqualTo(phase.equals("largeOutbox")?"HALTED":"RETRY_WAIT"); assertEmptyEffects();
            assertThat(count("inbox_failures")).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo(phase.equals("largeOutbox")?"PENDING":"NOT_REQUIRED");
            if(phase.equals("largeOutbox")) continue;
            due();
            // Same original succeeds under corrected test-only handler; no product recovery endpoint.
            var corrected=wrap(m->{ sql.update("INSERT INTO app.inbox_probe_business VALUES (?,?,?,'SUCCESS',1)",GROUP,m.businessId(),100); outbox.store(new Input(Kind.NotificationRequested,m.groupPurchaseId(),null,null,null,m.correlationId(),m.messageId(),Map.of())); });
            assertThat(processor(corrected).process(p,record).completed()).isTrue();
            assertThat(sql.queryForObject("SELECT recovery_state FROM app.inbox_failures",String.class)).isEqualTo("RECOVERED");
        }
        clear(); var p=owner(0); var business=UUID.randomUUID();
        assertThat(processor(wrap(m->{throw new IllegalStateException("after registration");})).process(p,raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()))).completed()).isFalse(); assertEmptyEffects();
        sql.execute("CREATE FUNCTION app.reject_inbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'probe registration'; END $$");
        sql.execute("CREATE TRIGGER reject_inbox BEFORE INSERT ON app.inbox_processed_messages FOR EACH ROW EXECUTE FUNCTION app.reject_inbox_insert()");
        try { due(); assertThat(processor().process(p,raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()))).completed()).isFalse(); assertEmptyEffects(); }
        finally { sql.execute("DROP TRIGGER reject_inbox ON app.inbox_processed_messages"); sql.execute("DROP FUNCTION app.reject_inbox_insert()"); }
    }
    @Test void actualParticipationAndFollowupOutboxShareInboxTransaction() {
        var p=owner(0); UUID purchase=OutboxIntegrationTests.purchase(app),business=UUID.randomUUID();
        var tree=JsonMapper.builder().build().readTree(sample(UUID.randomUUID(),business,Map.of()));
        ((tools.jackson.databind.node.ObjectNode)tree.get("context")).put("groupPurchaseId",purchase.toString());
        byte[] bytes=JsonMapper.builder().build().writeValueAsBytes(tree); var record=raw(0,0,business,bytes);
        long before=count("participations"); var fail=new AtomicBoolean(true);
        var composition=wrap(m->{
            UUID participation=app.getBean(ChangeParticipation.class).apply(UUID.randomUUID(),m.groupPurchaseId(),1,UUID.randomUUID().toString()).id();
            outbox.store(new Input(Kind.NotificationRequested,m.groupPurchaseId(),participation,null,null,m.correlationId(),m.messageId(),Map.of()));
            if(fail.get()) throw new IllegalStateException("composition rollback");
        });
        processor(composition).process(p,record);
        assertThat(count("participations")).isEqualTo(before); assertThat(count("inbox_processed_messages")).isZero(); assertThat(count("outbox_messages")).isZero();
        fail.set(false); due(); assertThat(processor(composition).process(p,record).completed()).isTrue();
        processor(composition).process(p,record);
        assertThat(count("participations")).isEqualTo(before+1); assertThat(count("outbox_messages")).isEqualTo(1);
    }
    @Test void originalConflictAndBusinessConflictAreDurableHolds() {
        var p=owner(0); UUID business=UUID.randomUUID(),id=UUID.randomUUID(); byte[] bytes=sample(id,business,Map.of());
        processor().process(p,raw(0,0,business,bytes));
        var conflict=raw(0,1,business,sample(id,business,Map.of("optional",true)));
        assertThat(processor().process(p,conflict).state()).isEqualTo("HALTED");
        assertThat(sql.queryForObject("SELECT error_code FROM app.inbox_failures",String.class)).isEqualTo("MESSAGE_ID_CONFLICT");
        assertThat(count("outbox_messages")).isEqualTo(1);
        var held=p;
        assertThatThrownBy(()->processor().process(held,raw(0,2,business,sample(UUID.randomUUID(),business,Map.of())))).isInstanceOf(JdbcInbox.PartitionHeld.class);
        clear(); p=owner(0); processor().process(p,raw(0,0,business,bytes));
        assertThat(processor().process(p,raw(0,1,business,ProbeSupport.sample(UUID.randomUUID(),business,"SUCCESS",200,Map.of()))).state()).isEqualTo("HALTED");
        assertThat(sql.queryForObject("SELECT error_code FROM app.inbox_failures",String.class)).isEqualTo("BUSINESS_CONFLICT");
    }
    @Test void malformedOriginalsAndHeadersAreNeverTruncatedOrNormalized() {
        UUID business=UUID.randomUUID();
        for(byte[] bytes:Arrays.asList(null,new byte[]{-1},new byte[65537],"{}".getBytes(StandardCharsets.UTF_8),sample(UUID.randomUUID(),business,Map.of()))) {
            clear(); var p=owner(0); var record=raw(0,0,business,bytes);
            if(bytes!=null && bytes.length>0 && bytes.length<65536 && bytes[0]=='{') record=new IncomingRecord(TOPIC,ID,0,0,null,bytes,record.headers(),1234,"CREATE_TIME",7);
            processor().process(p,record);
            var row=sql.queryForMap("SELECT * FROM app.inbox_failures");
            assertThat((byte[])row.get("raw_value")).isEqualTo(bytes); assertThat((byte[])row.get("raw_key")).isEqualTo(record.key());
            var headers=InboxBytes.headers((byte[])row.get("raw_headers")); assertThat(headers).hasSize(2); assertThat(headers.get(0).value()).isNull(); assertThat(headers.get(1).value()).containsExactly(0,-1);
            assertThat(inbox.read(p.key()).orElseThrow().next()).isZero(); assertEmptyEffects();
        }
        var nulls=new IncomingRecord(TOPIC,ID,0,0,null,null,List.of(),0,"NO_TIMESTAMP_TYPE",null);
        var empty=new IncomingRecord(TOPIC,ID,0,0,new byte[0],new byte[0],List.of(),0,"NO_TIMESTAMP_TYPE",null);
        assertThat(InboxBytes.fingerprint(nulls)).isNotEqualTo(InboxBytes.fingerprint(empty));
    }
    @Test void persistedFiveAttemptBudgetInterruptedAttemptsAndCompletedProofFirst() {
        var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of("fail",true)));
        var parsed=new InboxDecoder(Map.of(TOPIC,ProbeSupport.CANONICAL),List.of(handler())).decode(record);
        inbox.begin(p,record,parsed); // simulated crashed process: charged before handler work
        for(int n=2;n<=5;n++) {
            var result=processor().process(p,record); assertThat(result.state()).isEqualTo(n==5?"HALTED":"RETRY_WAIT");
            if(n<5) due();
        }
        assertThat(count("inbox_attempts")).isEqualTo(5);
        assertThat(sql.queryForObject("SELECT count(*) FROM app.inbox_attempts WHERE outcome='INTERRUPTED'",Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("PENDING");
        clear(); p=owner(0); business=UUID.randomUUID(); record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()));
        processor().process(p,record);
        for(int n=0;n<6;n++) assertThat(processor().process(p,record).completed()).isTrue();
        assertThat(count("outbox_messages")).isEqualTo(1); assertThat(count("inbox_failures")).isZero();
    }
    @Test void retryDeadlineAndBudgetSurviveOwnershipChangeAndFailureStoreMustCommitBeforeDlt() {
        var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of("fail",true)));
        assertThat(processor().process(p,record).state()).isEqualTo("RETRY_WAIT");
        var restored=inbox.restorePartition(p.key(),new JdbcInbox.Range(ID,3,0,10,0L),false,ID,3).partition();
        assertThat(restored.state()).isEqualTo("RETRY_WAIT");
        var initialRestored=restored;
        assertThatThrownBy(()->processor().process(initialRestored,record)).isInstanceOf(JdbcInbox.PartitionHeld.class);
        assertThat(count("inbox_attempts")).isEqualTo(1);
        for(int n=2;n<=5;n++) {
            due(); processor().process(restored,record);
            restored=inbox.restorePartition(restored.key(),new JdbcInbox.Range(ID,3,0,10,0L),false,ID,3).partition();
        }
        assertThat(restored.state()).isEqualTo("HALTED"); assertThat(count("inbox_attempts")).isEqualTo(5);
        clear(); var owner=owner(0); var poison=raw(0,0,business,new byte[]{-1});
        sql.execute("CREATE FUNCTION app.reject_inbox_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'probe failure persistence'; END $$");
        sql.execute("CREATE TRIGGER reject_failure BEFORE INSERT ON app.inbox_failures FOR EACH ROW EXECUTE FUNCTION app.reject_inbox_failure()");
        try {
            assertThatThrownBy(()->processor().process(owner,poison)).isInstanceOf(InboxProcessor.DatabaseUncertain.class);
            assertThat(count("inbox_failures")).isZero(); assertThat(inbox.read(owner.key()).orElseThrow().state()).isEqualTo("ACTIVE");
            assertThat(new DltPublisher(dlt,(id,bytes)->{throw new AssertionError("must not send before durable failure");},Duration.ofSeconds(60)).publishOne()).isFalse();
        } finally { sql.execute("DROP TRIGGER reject_failure ON app.inbox_failures");sql.execute("DROP FUNCTION app.reject_inbox_failure()"); }
        assertThat(processor().process(owner,poison).state()).isEqualTo("HALTED");
    }
    @Test void databaseCommonFailureIsExcludedAndDoesNotCreateDlt() {
        var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()));
        var handler=wrap(m->{throw new RuntimeException(new java.sql.SQLException("probe connection","08006"));});
        assertThatThrownBy(()->processor(handler).process(p,record)).isInstanceOf(InboxProcessor.DatabaseUncertain.class);
        assertThat(count("inbox_failures")).isZero(); assertEmptyEffects();
        assertThat(sql.queryForObject("SELECT budget_charged FROM app.inbox_attempts",Boolean.class)).isFalse();
        assertThat(processor().process(p,record).completed()).isTrue();
    }
    @Test void isolatedRecoveryDriverKeepsFollowingWorkBlockedUntilSameOriginalCommits() {
        var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()));
        var rejecting=processor(wrap(m->{throw new MessageRejected("TEST_BUSINESS_REJECTION");}));
        assertThat(rejecting.process(p,record).state()).isEqualTo("HALTED");
        new DltPublisher(dlt,(id,bytes)->new DltTransport.Ack(0,0),Duration.ofSeconds(60)).publishOne();
        assertThatThrownBy(()->processor().process(p,raw(0,1,business,sample(UUID.randomUUID(),business,Map.of())))).isInstanceOf(JdbcInbox.PartitionHeld.class);
        // Test-only recovery driver: emulate an authorized retry of this exact source. No product API.
        inbox.transaction(()->{inbox.lock(p);sql.update("UPDATE app.inbox_partition_states SET state='RETRY_WAIT',retry_at=clock_timestamp() WHERE consumer_group=?",GROUP);return null;});
        assertThat(rejecting.process(p,record).state()).isEqualTo("HALTED"); assertEmptyEffects();
        inbox.transaction(()->{inbox.lock(p);sql.update("UPDATE app.inbox_partition_states SET state='RETRY_WAIT',retry_at=clock_timestamp() WHERE consumer_group=?",GROUP);return null;});
        assertThat(processor().process(p,record).completed()).isTrue();
        assertThat(inbox.read(p.key()).orElseThrow().state()).isEqualTo("ACTIVE");
        assertThat(sql.queryForObject("SELECT recovery_state FROM app.inbox_failures",String.class)).isEqualTo("RECOVERED");
        assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("DLT_STORED");
        assertThat(processor().process(p,raw(0,1,business,sample(UUID.randomUUID(),business,Map.of()))).completed()).isTrue();
    }
    @Test void dltDbFirstLosslessDocumentAckDoesNotAdvanceOrUnpauseSource() {
        var p=owner(0); UUID business=UUID.randomUUID(); var record=raw(0,0,business,new byte[]{-1,0,1}); processor().process(p,record);
        var seen=new AtomicReference<byte[]>();
        DltTransport transport=(id,bytes)-> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("inbox_failures")).isEqualTo(1); assertThat(inbox.read(p.key()).orElseThrow().state()).isEqualTo("HALTED");
            seen.set(bytes); return new DltTransport.Ack(2,7);
        };
        assertThat(new DltPublisher(dlt,transport,Duration.ofSeconds(60)).publishOne()).isTrue();
        var document=JsonMapper.builder().build().readTree(seen.get());
        assertThat(Base64.getDecoder().decode(document.get("valueBase64").asString())).containsExactly(-1,0,1);
        assertThat(document.get("headers").size()).isEqualTo(2); assertThat(document.get("headers").get(0).get("valueBase64").isNull()).isTrue();
        assertThat(inbox.read(p.key()).orElseThrow().state()).isEqualTo("HALTED"); assertThat(inbox.read(p.key()).orElseThrow().next()).isZero();
        assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("DLT_STORED");
    }
    @Test void dltLeaseExpiryLateResultsBrokerFailureAndOversizeRemainHeld() {
        var p=owner(0); var business=UUID.randomUUID(); processor().process(p,raw(0,0,business,new byte[]{-1}));
        var old=dlt.claim(Duration.ofSeconds(60)).orElseThrow();
        assertThat(dlt.claim(Duration.ofSeconds(60))).isEmpty();
        sql.update("UPDATE app.inbox_failures SET dlt_claim_until=clock_timestamp()-interval '1s'");
        var current=dlt.claim(Duration.ofSeconds(60)).orElseThrow(); assertThat(current.failureId()).isEqualTo(old.failureId());
        assertThat(dlt.complete(old,0,1)).isFalse(); assertThat(dlt.fail(old,true,"TIMEOUT",Duration.ZERO)).isFalse();
        dlt.fail(current,true,"BROKER_TIMEOUT",Duration.ZERO);
        new DltPublisher(dlt,(id,bytes)->{throw new org.apache.kafka.common.errors.TimeoutException("test");},Duration.ofSeconds(60)).publishOne();
        assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("RETRY_WAIT");
        sql.update("UPDATE app.inbox_failures SET dlt_next_at=clock_timestamp()");
        new DltPublisher(dlt,(id,bytes)->{throw new org.apache.kafka.common.errors.RecordTooLargeException("test");},Duration.ofSeconds(60)).publishOne();
        assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("DLT_BLOCKED");
        assertThat(inbox.read(p.key()).orElseThrow().state()).isEqualTo("HALTED");
        clear(); p=owner(0); processor().process(p,raw(0,0,business,new byte[1048576]));
        AtomicInteger size=new AtomicInteger(); new DltPublisher(dlt,(id,bytes)->{size.set(bytes.length); return new DltTransport.Ack(0,0);},Duration.ofSeconds(60)).publishOne();
        assertThat(size.get()).isBetween(1398100,1500000);
        try(var transport=new KafkaDltTransport(new OutboxSettings(10000,50,10000,60000,"localhost:1",10000,10000,30000,35000),"unused")) {
            assertThatThrownBy(()->transport.send(UUID.randomUUID(),new byte[4194304])).isInstanceOf(org.apache.kafka.common.errors.RecordTooLargeException.class);
            clear(); var oversizedOwner=owner(0);
            var oversized=new IncomingRecord(TOPIC,ID,0,0,null,new byte[1048576],List.of(new IncomingRecord.Header("big",new byte[2300000])),0,"CREATE_TIME",null);
            processor().process(oversizedOwner,oversized);
            new DltPublisher(dlt,transport,Duration.ofSeconds(60)).publishOne();
            assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("DLT_BLOCKED");
            assertThat(sql.queryForObject("SELECT octet_length(raw_value) FROM app.inbox_failures",Integer.class)).isEqualTo(1048576);
        }
    }
    @Test void offsetRangeIdentityAndOwnerFencingAndReplayedUnknownId() {
        var p=owner(0); var business=UUID.randomUUID(); processor().process(p,raw(0,0,business,sample(UUID.randomUUID(),business,Map.of())));
        var restored=inbox.restorePartition(p.key(),new JdbcInbox.Range(ID,3,0,10,null),false,ID,3);
        assertThat(restored.seek()).isEqualTo(1); assertThat(restored.restoreCommit()).isTrue();
        var previous=p;
        assertThatThrownBy(()->inbox.transaction(()->inbox.lock(previous))).isInstanceOf(JdbcInbox.OwnershipLost.class);
        assertThat(processor().process(restored.partition(),raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()))).state()).isEqualTo("OFFSET_GAP");
        for(var range:List.of(new JdbcInbox.Range(ID,3,2,10,1L),new JdbcInbox.Range(ID,3,0,10,2L),new JdbcInbox.Range(ID,3,0,0,0L),new JdbcInbox.Range("recreated",3,0,10,0L),new JdbcInbox.Range(ID,4,0,10,0L))) {
            clear(); p=owner(0); processor().process(p,raw(0,0,business,sample(UUID.randomUUID(),business,Map.of())));
            assertThat(inbox.restorePartition(p.key(),range,false,ID,3).partition().state()).isEqualTo("OFFSET_GAP");
        }
        clear(); assertThat(inbox.restorePartition(new JdbcInbox.Key(GROUP,TOPIC,0,ROLE),new JdbcInbox.Range(ID,3,0,10,null),false,ID,3).partition().state()).isEqualTo("OFFSET_GAP");
    }
    @Test void actualCommitResponseLossReconcilesCompletedBusinessBeforeRetry() throws Exception {
        var p=owner(0); var business=UUID.randomUUID(); var record=raw(0,0,business,sample(UUID.randomUUID(),business,Map.of()));
        var uri=java.net.URI.create(db.url().substring("jdbc:".length()));
        try(var proxy=new CommitResponseDropProxy(uri.getHost(),uri.getPort())) {
            String url="jdbc:postgresql://127.0.0.1:"+proxy.port()+"/group_purchase?currentSchema=app&sslmode=disable&connectTimeout=3&socketTimeout=5";
            var ds=new DriverManagerDataSource(url,"group_purchase_app",db.password()); var jdbc=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds);
            var proxied=new JdbcInbox(jdbc,manager); var proxiedOutbox=new JdbcOutbox(jdbc,manager);
            var base=ProbeSupport.handler(jdbc,proxiedOutbox,GROUP,null);
            var processor=new InboxProcessor(proxied,new InboxDecoder(Map.of(TOPIC,ProbeSupport.CANONICAL),List.of(wrap(m->{base.handle(m);proxy.armed.set(true);}))),metrics);
            assertThatThrownBy(()->processor.process(p,record)).isInstanceOf(InboxProcessor.DatabaseUncertain.class);
            assertThat(proxy.dropped).isTrue(); assertThat(count("inbox_processed_messages")).isEqualTo(1); assertThat(count("outbox_messages")).isEqualTo(1);
            assertThat(processor.process(p,record).completed()).isTrue(); assertThat(count("outbox_messages")).isEqualTo(1); assertThat(count("inbox_failures")).isZero();
        }
    }
    static void await(java.util.function.BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    record Topic(String name,String group,String id) { }
    Topic topic() throws Exception {
        String name="verify.inbox."+UUID.randomUUID().toString().replace("-","");
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name,3,(short)1),new NewTopic(name+".dlt",3,(short)1).configs(Map.of("max.message.bytes","4194304")))).all().get(15,TimeUnit.SECONDS);
            return new Topic(name,name+".group",admin.describeTopics(List.of(name)).allTopicNames().get().get(name).topicId().toString());
        }
    }
    InboxSettings settings(Topic t) { return new InboxSettings(true,broker.getBootstrapServers(),t.name(),t.group(),t.group()+".role","from-origin",t.id(),3,20); }
    void send(Topic t,int partition,UUID business,byte[] bytes) throws Exception {
        try(var producer=new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",broker.getBootstrapServers(),"key.serializer",ByteArraySerializer.class,"value.serializer",ByteArraySerializer.class,"acks","all"))) {
            producer.send(new ProducerRecord<>(t.name(),partition,business.toString().getBytes(StandardCharsets.UTF_8),bytes)).get(15,TimeUnit.SECONDS);
        }
    }
    InboxConsumerLoop loop(Topic t) {
        var decoder=new InboxDecoder(Map.of(t.name(),ProbeSupport.CANONICAL),List.of(ProbeSupport.handler(sql,outbox,t.group(),null)));
        return new InboxConsumerLoop(settings(t),inbox,new InboxProcessor(inbox,decoder,new SimpleMeterRegistry()),new SimpleMeterRegistry());
    }
    Long committed(Topic t,int partition) throws Exception {
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            var value=admin.listConsumerGroupOffsets(t.group()).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(new TopicPartition(t.name(),partition));
            return value==null?null:value.offset();
        }
    }
    @Test void actualTwoConsumersRebalanceRestoreHoldAndNeverCommitLaterBatchRecord() throws Exception {
        var t=topic(); var business=UUID.randomUUID();
        send(t,0,business,new byte[]{-1}); send(t,0,business,sample(UUID.randomUUID(),business,Map.of()));
        send(t,1,UUID.randomUUID(),new byte[]{-1}); // replace below with valid matching key on another partition
        var normal=UUID.randomUUID(); send(t,2,normal,sample(UUID.randomUUID(),normal,Map.of()));
        var first=loop(t); var second=loop(t);
        try {
            first.start(); await(()->count("inbox_failures")==2 && count("inbox_processed_messages")==1,35);
            assertThat(committed(t,0)).isEqualTo(0); assertThat(committed(t,2)).isEqualTo(1);
            var key=new JdbcInbox.Key(t.group(),t.name(),0,settings(t).role()); var old=inbox.read(key).orElseThrow();
            second.start(); await(()->!inbox.read(key).orElseThrow().token().equals(old.token()),35);
            assertThat(inbox.read(key).orElseThrow().state()).isEqualTo("HALTED");
            first.close(); await(()->sql.queryForObject("SELECT count(*) FROM app.inbox_partition_states WHERE consumer_group=?",Long.class,t.group())==3,20);
            var next=UUID.randomUUID(); send(t,2,next,sample(UUID.randomUUID(),next,Map.of()));
            await(()->count("inbox_processed_messages")==2,35);
            assertThat(committed(t,0)).isEqualTo(0); assertThat(inbox.read(key).orElseThrow().next()).isZero();
            try(var kafka=new KafkaDltTransport(new OutboxSettings(10000,50,10000,60000,broker.getBootstrapServers(),10000,10000,30000,35000),t.name()+".dlt")) {
                new DltPublisher(dlt,kafka,Duration.ofSeconds(60)).publishOne();
            }
            assertThat(inbox.read(key).orElseThrow().state()).isEqualTo("HALTED");
        } finally { first.close(); second.close(); }
    }
    @Test void realBrokerRetentionCommitLossForwardCommitAndTopicRecreationAreDetected() throws Exception {
        var t=topic(); var business=UUID.randomUUID(); byte[] bytes=sample(UUID.randomUUID(),business,Map.of());
        send(t,0,business,bytes); send(t,0,business,sample(UUID.randomUUID(),business,Map.of()));
        var key=new JdbcInbox.Key(t.group(),t.name(),0,settings(t).role()); var partition=new TopicPartition(t.name(),0);
        try(var c=new KafkaConsumer<byte[],byte[]>(settings(t).consumerProperties());var metadata=InboxMetadata.kafka(broker.getBootstrapServers());
                var admin=Admin.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            c.assign(List.of(partition)); var restored=inbox.restorePartition(key,metadata.range(c,partition),true,t.id(),3);
            var decoder=new InboxDecoder(Map.of(t.name(),ProbeSupport.CANONICAL),List.of(ProbeSupport.handler(sql,outbox,t.group(),null)));
            var processor=new InboxProcessor(inbox,decoder,metrics);
            var r=new IncomingRecord(t.name(),t.id(),0,0,business.toString().getBytes(StandardCharsets.UTF_8),bytes,List.of(),0,"CREATE_TIME",0);
            processor.process(restored.partition(),r);
            assertThat(inbox.restorePartition(key,metadata.range(c,partition),false,t.id(),3).seek()).isEqualTo(1); // K absent, D proves prefix
            c.commitSync(Map.of(partition,new OffsetAndMetadata(2)),Duration.ofSeconds(5));
            assertThat(inbox.restorePartition(key,metadata.range(c,partition),false,t.id(),3).partition().state()).isEqualTo("OFFSET_GAP");
            // Isolated new group has an unprocessed D=0; broker deletes the prefix.
            var retainedKey=new JdbcInbox.Key(t.group()+".retained",t.name(),0,settings(t).role());
            inbox.restorePartition(retainedKey,new JdbcInbox.Range(t.id(),3,0,2,null),true,t.id(),3);
            admin.deleteRecords(Map.of(partition,RecordsToDelete.beforeOffset(1))).all().get(10,TimeUnit.SECONDS);
            assertThat(c.beginningOffsets(List.of(partition)).get(partition)).isEqualTo(1);
            assertThat(inbox.restorePartition(retainedKey,new JdbcInbox.Range(t.id(),3,1,2,null),false,t.id(),3).partition().state()).isEqualTo("OFFSET_GAP");
            admin.deleteTopics(List.of(t.name())).all().get(10,TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(t.name(),3,(short)1))).all().get(15,TimeUnit.SECONDS);
            var topicId=new AtomicReference<String>();
            await(()->{
                try { topicId.set(admin.describeTopics(List.of(t.name())).allTopicNames().get(2,TimeUnit.SECONDS).get(t.name()).topicId().toString());return !topicId.get().equals(t.id()); }
                catch(Exception unavailable) { return false; }
            },15);
            String recreated=topicId.get();
            assertThat(recreated).isNotEqualTo(t.id());
            assertThat(inbox.restorePartition(key,new JdbcInbox.Range(recreated,3,0,0,null),false,t.id(),3).partition().state()).isEqualTo("OFFSET_GAP");
        }
    }
    @Test void realBrokerCommitPersistsButClientLosesAcknowledgementWithoutRepeatingBusiness() throws Exception {
        var t=topic();var business=UUID.randomUUID();send(t,0,business,sample(UUID.randomUUID(),business,Map.of()));
        var dropped=new AtomicBoolean();var registry=new SimpleMeterRegistry();
        var decoder=new InboxDecoder(Map.of(t.name(),ProbeSupport.CANONICAL),List.of(ProbeSupport.handler(sql,outbox,t.group(),null)));
        var loop=new InboxConsumerLoop(settings(t),inbox,new InboxProcessor(inbox,decoder,registry),registry,
                ()->new KafkaConsumer<byte[],byte[]>(settings(t).consumerProperties()) {
                    @Override public void commitSync(Map<TopicPartition,OffsetAndMetadata> offsets,Duration timeout) {
                        super.commitSync(offsets,timeout);
                        if(offsets.values().stream().anyMatch(o->o.offset()>0) && dropped.compareAndSet(false,true))
                            throw new org.apache.kafka.common.errors.TimeoutException("test: durable broker commit, client ACK lost");
                    }
                },()->InboxMetadata.kafka(broker.getBootstrapServers()));
        try { loop.start();await(()->dropped.get() && registry.counter("inbox.commit.failures").count()==1,35); }
        finally { loop.close(); }
        assertThat(committed(t,0)).isEqualTo(1); assertThat(count("outbox_messages")).isEqualTo(1);
        var restart=loop(t);
        try {restart.start();await(()->restart.status().equals("RUNNING"),15);}finally{restart.close();}
        assertThat(count("inbox_processed_messages")).isEqualTo(1);assertThat(count("outbox_messages")).isEqualTo(1);
        assertThat(registry.timer("inbox.poll.interval").max(TimeUnit.SECONDS)).isLessThan(290);
    }
    @Test void explicitCommitTimeoutRetriesOnlySafePrefixAndLostAssignmentNeverCommits() throws Exception {
        var business=UUID.randomUUID(); var t=new Topic(TOPIC,GROUP,ID); var commits=new CopyOnWriteArrayList<Map<TopicPartition,OffsetAndMetadata>>();
        AtomicInteger calls=new AtomicInteger(); var lost=new AtomicBoolean();
        var mock=new MockConsumer<byte[],byte[]>(OffsetResetStrategy.NONE) {
            ConsumerRebalanceListener listener;
            @Override public synchronized void subscribe(Collection<String> topics,ConsumerRebalanceListener listener) { this.listener=listener; super.subscribe(topics,listener); }
            @Override public synchronized void commitSync(Map<TopicPartition,OffsetAndMetadata> values,Duration timeout) {
                commits.add(Map.copyOf(values));
                if(calls.getAndIncrement()==0) throw new org.apache.kafka.common.errors.TimeoutException("ACK unknown");
                super.commitSync(values,timeout);
                if(!lost.getAndSet(true)) schedulePollTask(()->{
                    listener.onPartitionsLost(new ArrayList<>(assignment())); rebalance(List.of());
                });
            }
        };
        var p0=new TopicPartition(TOPIC,0); var p1=new TopicPartition(TOPIC,1);
        mock.schedulePollTask(()->{mock.rebalance(List.of(p0,p1));mock.updateBeginningOffsets(Map.of(p0,0L,p1,0L));mock.updateEndOffsets(Map.of(p0,3L,p1,1L));});
        // Assignment preparation restores a safe zero first; inject records once both are ready.
        mock.schedulePollTask(()->{});
        mock.schedulePollTask(()->{
            if(mock.assignment().isEmpty()) return;
            mock.addRecord(new ConsumerRecord<>(TOPIC,0,0,business.toString().getBytes(StandardCharsets.UTF_8),new byte[]{-1}));
            mock.addRecord(new ConsumerRecord<>(TOPIC,0,1,business.toString().getBytes(StandardCharsets.UTF_8),sample(UUID.randomUUID(),business,Map.of())));
            mock.addRecord(new ConsumerRecord<>(TOPIC,1,0,business.toString().getBytes(StandardCharsets.UTF_8),sample(UUID.randomUUID(),business,Map.of())));
        });
        // Pure mock callbacks cover timeout/lost independently of the real rebalance test.
        var decoder=new InboxDecoder(Map.of(TOPIC,ProbeSupport.CANONICAL),List.of(handler()));
        var loop=new InboxConsumerLoop(settings(t),inbox,new InboxProcessor(inbox,decoder,metrics),metrics,()->mock,
                ()->(c,p)->new JdbcInbox.Range(ID,3,0,10,null));
        try { loop.start(); await(()->lost.get(),10); } finally { loop.close(); }
        assertThat(commits).isNotEmpty();
        for(var values:commits) if(values.containsKey(p0)) assertThat(values.get(p0).offset()).isZero();
        assertThat(metrics.counter("inbox.commit.failures").count()).isEqualTo(1);
    }
    Process probe(String mode,Topic topic,Path directory) throws Exception {
        Files.createDirectories(directory);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new ProcessBuilder(java,"-jar",System.getProperty("test.inbox-jar"),mode,db.url(),"group_purchase_app",db.secretDirectory.resolve("spring.datasource.password").toString(),
                broker.getBootstrapServers(),topic.name(),topic.group(),directory.toString(),"45000").redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
    }
    void killedAt(String mode,String marker,Topic topic,Path directory) throws Exception {
        var child=probe(mode,topic,directory);
        try {
            await(()->Files.exists(directory.resolve(marker+".marker")) || !child.isAlive(),45);
            assertThat(Files.exists(directory.resolve(marker+".marker"))).withFailMessage(Files.readString(directory.resolve(mode+".log"))).isTrue();
        } finally { child.destroyForcibly(); assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue(); }
    }
    void runProbe(String mode,Topic topic,Path directory) throws Exception {
        var child=probe(mode,topic,directory);
        try { assertThat(child.waitFor(75,TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).withFailMessage(Files.readString(directory.resolve(mode+".log"))).isZero(); }
        finally { if(child.isAlive()) {child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);} }
    }
    @Test void realProcessKillsBeforeAndAfterDbCommitAndBeforeAndAfterDltAck() throws Exception {
        Path root=Files.createTempDirectory("inbox-kill-");
        for(String mode:List.of("before-db","after-db")) {
            clear(); var t=topic(); var business=UUID.randomUUID(); send(t,0,business,sample(UUID.randomUUID(),business,Map.of()));
            var directory=Files.createDirectory(root.resolve(mode)); killedAt(mode,mode.equals("before-db")?"before-db-commit":"after-db-commit",t,directory);
            assertThat(count("inbox_processed_messages")).isEqualTo(mode.equals("before-db")?0:1);
            runProbe("once",t,directory);
            assertThat(count("inbox_processed_messages")).isEqualTo(1); assertThat(count("outbox_messages")).isEqualTo(1);
            assertThat(committed(t,0)).isEqualTo(1); assertThat(count("inbox_failures")).isZero();
        }
        for(String mode:List.of("dlt-before","dlt-after")) {
            clear(); var t=topic(); var business=UUID.randomUUID(); send(t,0,business,new byte[]{-1});
            Path directory=Files.createDirectory(root.resolve(mode)); runProbe("once",t,directory);
            UUID id=sql.queryForObject("SELECT failure_id FROM app.inbox_failures",UUID.class);
            killedAt(mode,mode.equals("dlt-before")?"before-dlt-send":"after-dlt-ack",t,directory);
            Thread.sleep(3100); runProbe("dlt-once",t,directory);
            assertThat(sql.queryForObject("SELECT failure_id FROM app.inbox_failures",UUID.class)).isEqualTo(id);
            assertThat(sql.queryForObject("SELECT dlt_state FROM app.inbox_failures",String.class)).isEqualTo("DLT_STORED");
            assertThat(sql.queryForObject("SELECT state FROM app.inbox_partition_states",String.class)).isEqualTo("HALTED");
            assertThat(committed(t,0)).isNull(); assertThat(count("inbox_processed_messages")).isZero();
        }
        System.out.println("INBOX_PROCESS_BOUNDARIES_OK diagnostics="+root);
    }
}
