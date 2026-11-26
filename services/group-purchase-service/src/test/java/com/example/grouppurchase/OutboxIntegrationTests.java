package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.*;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage.*;
import com.example.grouppurchase.outbox.infrastructure.*;
import com.example.grouppurchase.infrastructure.messaging.*;
import com.example.grouppurchase.config.OutboxSettings;
import com.example.grouppurchase.participation.application.ChangeParticipation;
import com.example.grouppurchase.purchase.application.*;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.kafka.KafkaContainer;

class OutboxIntegrationTests {
    static PostgresFixture db;
    static ConfigClientIntegrationTests.ConfigServerFixture config;
    static ConfigurableApplicationContext app;
    static JdbcOutbox outbox;
    static JdbcTemplate sql;
    static TransactionTemplate tx;
    static UUID migratedPurchase;
    static UUID migratedParticipation;

    static ConfigurableApplicationContext start(String... extra) {
        var args = new ArrayList<>(List.of("--spring.profiles.active=local", "--CONFIG_SERVER_URL=" + config.url(),
                "--spring.cloud.config.enabled=true", "--eureka.client.enabled=false", "--server.port=0", "--app.outbox.enabled=false"));
        args.addAll(List.of(extra));
        return db.configure(new SpringApplicationBuilder(GroupPurchaseApplication.class), "local").run(args.toArray(String[]::new));
    }
    @BeforeAll static void setup() throws Exception {
        db = new PostgresFixture();
        config = new ConfigClientIntegrationTests.ConfigServerFixture(Path.of(System.getProperty("test.config-repo")));
        try (var old = start("--spring.flyway.target=3")) {
            migratedPurchase = purchase(old);
            migratedParticipation = old.getBean(ChangeParticipation.class).apply(UUID.randomUUID(), migratedPurchase, 1, key()).id();
        }
        app = start(); outbox = app.getBean(JdbcOutbox.class); sql = app.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void cleanup() throws Exception {
        try { if (app != null) app.close(); } finally {
            try { if (config != null) config.close(); } finally { if (db != null) db.close(); }
        }
    }
    @BeforeEach void clear() {
        sql.execute("TRUNCATE app.outbox_delivery_attempts,app.outbox_messages,app.outbox_streams");
    }
    static String key() { return UUID.randomUUID().toString(); }
    static UUID purchase(ConfigurableApplicationContext context) {
        var change = context.getBean(ChangeGroupPurchase.class);
        UUID seller = UUID.randomUUID();
        UUID id = change.register(seller,key(),new SellingTerms("outbox", "test",100L,100,Instant.parse("2090-01-01T00:00:00Z"))).id();
        change.changeDraft(seller,key(),new PurchaseChange(PurchaseChange.Operation.PUBLISH,id,1L,null));
        return id;
    }
    static Input input(UUID attempt) {
        return new Input(Kind.PaymentApprovalRequested,UUID.randomUUID(),UUID.randomUUID(),attempt,null,UUID.randomUUID(),null,Map.of("sample",true));
    }
    Stored store(Input input) { return tx.execute(s -> outbox.store(input)); }
    long count(String table) { return sql.queryForObject("SELECT count(*) FROM app." + table, Long.class); }
    String status(UUID id) { return sql.queryForObject("SELECT status FROM app.outbox_messages WHERE message_id=?",String.class,id); }
    void expire(UUID id) { sql.update("UPDATE app.outbox_messages SET claim_until=clock_timestamp()-interval '1 second' WHERE message_id=?",id); }
    void due(UUID id) { sql.update("UPDATE app.outbox_messages SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE message_id=?",id); }

    @Test void migrationPreservesV3ParticipationAndIdempotency() {
        assertThat(sql.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success AND version IN ('1','2','3','4')",Integer.class)).isEqualTo(4);
        assertThat(sql.queryForObject("SELECT quantity FROM app.participations WHERE id=?",Integer.class,migratedParticipation)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM app.participation_idempotency WHERE participation_id=?",Integer.class,migratedParticipation)).isEqualTo(1);
    }
    @Test void mandatoryTransactionAndWholeBusinessRollback() {
        assertThatThrownBy(() -> outbox.store(input(UUID.randomUUID()))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("outbox_streams")).isZero();
        var composition = new TestComposition(app.getBean(ChangeParticipation.class), outbox);
        UUID purchase = purchase(app), attempt = UUID.randomUUID();
        long before = count("participations"), receipts = count("participation_idempotency");
        assertThatThrownBy(() -> tx.execute(s -> composition.apply(purchase,attempt,true,false))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.execute(s -> composition.apply(purchase,attempt,false,true))).isInstanceOf(RuntimeException.class);
        assertThat(count("participations")).isEqualTo(before); assertThat(count("participation_idempotency")).isEqualTo(receipts);
        assertThat(count("outbox_streams")).isZero(); assertThat(count("outbox_messages")).isZero();
        assertThat(sql.queryForObject("SELECT recruited_quantity FROM app.group_purchases WHERE id=?",Integer.class,purchase)).isZero();
        var saved = tx.execute(s -> composition.apply(purchase,attempt,false,false));
        assertThat(saved.sequence()).isEqualTo(1); assertThat(count("participations")).isEqualTo(before+1);
        assertThat(count("participation_idempotency")).isEqualTo(receipts+1);
        assertThat(sql.queryForObject("SELECT recruited_quantity FROM app.group_purchases WHERE id=?",Integer.class,purchase)).isEqualTo(1);
    }
    /** Test-only business + outbox composition. Product participation API is unchanged. */
    record TestComposition(ChangeParticipation participation, StoreOutgoingMessage outbox) {
        Stored apply(UUID purchase,UUID attempt,boolean invalid,boolean after) {
            UUID id = participation.apply(UUID.randomUUID(),purchase,1,key()).id();
            var result = outbox.store(new Input(Kind.PaymentApprovalRequested,purchase,id,attempt,null,UUID.randomUUID(),null,
                    Map.of("sample", invalid ? "x".repeat(65536) : "ok")));
            if (after) throw new IllegalStateException("after outbox insert");
            return result;
        }
    }
    @Test void concurrentSequencesRollbackOverflowAndNotificationShape() throws Exception {
        UUID attempt = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<Stored>>();
            for (int i=0;i<12;i++) tasks.add(pool.submit(() -> store(input(attempt))));
            var sequences = new TreeSet<Long>();
            for (var task : tasks) sequences.add(task.get(30,TimeUnit.SECONDS).sequence());
            assertThat(sequences).containsExactly(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L);
        }
        assertThatThrownBy(() -> tx.execute(s -> { outbox.store(input(attempt)); throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        assertThat(store(input(attempt)).sequence()).isEqualTo(13);
        assertThat(store(input(UUID.randomUUID())).sequence()).isEqualTo(1);
        sql.update("UPDATE app.outbox_streams SET last_sequence=9223372036854775807 WHERE payment_attempt_id=?",attempt);
        assertThatThrownBy(() -> store(input(attempt))).hasMessageContaining("exhausted");
        var n = store(new Input(Kind.NotificationRequested,UUID.randomUUID(),null,null,null,UUID.randomUUID(),null,Map.of()));
        assertThat(n.sequence()).isNull();
        byte[] raw = sql.queryForObject("SELECT envelope FROM app.outbox_messages WHERE message_id=?",byte[].class,n.messageId());
        assertThat(new MessageCodec().decode(raw).envelope().has("sequence")).isFalse();
    }
    @Test void twoClaimersSkipLockedHeadAndBlockEveryUnfinishedState() throws Exception {
        UUID attempt = UUID.randomUUID(); var first = store(input(attempt)); var second = store(input(attempt));
        var other = store(input(UUID.randomUUID()));
        try (var c = db.connection()) {
            c.setAutoCommit(false);
            try (var q = c.prepareStatement("SELECT 1 FROM app.outbox_messages WHERE message_id=? FOR UPDATE")) { q.setObject(1,first.messageId()); q.executeQuery().close(); }
            var independent = outbox.claim(Duration.ofSeconds(60)).orElseThrow();
            assertThat(independent.messageId()).isEqualTo(other.messageId()); outbox.complete(independent,0,0);
            assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty(); c.rollback();
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Optional<JdbcOutbox.Claim>> claim = () -> { gate.await(); return outbox.claim(Duration.ofSeconds(60)); };
            var a=pool.submit(claim); var b=pool.submit(claim); gate.countDown();
            var claims = List.of(a.get(),b.get()).stream().flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1); var owner=claims.getFirst();
            assertThat(owner.messageId()).isEqualTo(first.messageId());
            assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty();
            assertThat(outbox.fail(owner,true,"TimeoutException",Duration.ofSeconds(60))).isTrue();
        }
        assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty(); due(first.messageId());
        var retry=outbox.claim(Duration.ofSeconds(60)).orElseThrow();
        outbox.fail(retry,false,"AuthorizationException",Duration.ZERO);
        assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty();
        assertThat(status(second.messageId())).isEqualTo("READY");
    }
    @Test void expiryReclaimAndLateResultsNeverOverwriteNewOwnerOrHistory() {
        var saved=store(input(UUID.randomUUID())); var old=outbox.claim(Duration.ofSeconds(60)).orElseThrow();
        expire(saved.messageId()); assertThat(outbox.complete(old,0,1)).isFalse();
        assertThat(outbox.fail(old,true,"late",Duration.ZERO)).isFalse();
        var current=outbox.claim(Duration.ofSeconds(60)).orElseThrow();
        assertThat(current.claimId()).isNotEqualTo(old.claimId()); assertThat(current.raw()).isEqualTo(old.raw());
        assertThat(outbox.complete(old,0,1)).isFalse(); assertThat(outbox.fail(old,false,"late",Duration.ZERO)).isFalse();
        assertThat(outbox.complete(current,2,9)).isTrue(); assertThat(outbox.complete(current,2,9)).isFalse();
        assertThat(sql.queryForList("SELECT outcome FROM app.outbox_delivery_attempts ORDER BY claim_number",String.class)).containsExactly("EXPIRED","PUBLISHED");
        assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty();
    }
    @Test void retryPersistsOriginalFirstFailureAndSuspensionBlocksStream() {
        var first=store(input(UUID.randomUUID()));
        var publisher=new OutboxPublisher(outbox,m -> { throw new org.apache.kafka.common.errors.TimeoutException("unknown delivery"); },Duration.ofSeconds(60));
        publisher.publishOne(); var row=sql.queryForMap("SELECT * FROM app.outbox_messages WHERE message_id=?",first.messageId());
        assertThat(row.get("status")).isEqualTo("RETRY_WAIT"); assertThat(row.get("retry_count")).isEqualTo(1L);
        due(first.messageId()); publisher.publishOne();
        var again=sql.queryForMap("SELECT * FROM app.outbox_messages WHERE message_id=?",first.messageId());
        assertThat(again.get("first_failure_at")).isEqualTo(row.get("first_failure_at")); assertThat(again.get("retry_count")).isEqualTo(2L);
        assertThat((byte[])again.get("envelope")).isEqualTo((byte[])row.get("envelope"));
        due(first.messageId());
        new OutboxPublisher(outbox,m -> { throw new org.apache.kafka.common.errors.TopicAuthorizationException(Set.of(m.topic())); },Duration.ofSeconds(60)).publishOne();
        assertThat(status(first.messageId())).isEqualTo("SUSPENDED");
        assertThat(sql.queryForList("SELECT outcome FROM app.outbox_delivery_attempts ORDER BY claim_number",String.class))
                .containsExactly("RETRY_WAIT","RETRY_WAIT","SUSPENDED");
    }
    @Test void lockWaitCannotExtendACompletionLease() throws Exception {
        var saved=store(input(UUID.randomUUID())); var claim=outbox.claim(Duration.ofMillis(500)).orElseThrow();
        try(var c=db.connection();var statement=c.createStatement();var pool=Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            statement.executeQuery("SELECT 1 FROM app.outbox_messages WHERE message_id='"+saved.messageId()+"' FOR UPDATE").close();
            var result=pool.submit(() -> outbox.complete(claim,0,1));
            Thread.sleep(750);c.rollback(); assertThat(result.get(5,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(status(saved.messageId())).isEqualTo("IN_FLIGHT");
    }
    @Test void insertFailureRollsBackBusinessAndCorruptOriginalSuspendsBeforeKafka() {
        UUID purchase=purchase(app),attempt=UUID.randomUUID(); long before=count("participations");
        sql.execute("CREATE FUNCTION app.reject_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated insert failure'; END $$");
        sql.execute("CREATE TRIGGER reject_insert BEFORE INSERT ON app.outbox_messages FOR EACH ROW EXECUTE FUNCTION app.reject_outbox_insert()");
        try {
            assertThatThrownBy(() -> tx.execute(s -> new TestComposition(app.getBean(ChangeParticipation.class),outbox).apply(purchase,attempt,false,false)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("participations")).isEqualTo(before); assertThat(count("outbox_streams")).isZero();
        } finally {sql.execute("DROP TRIGGER reject_insert ON app.outbox_messages");sql.execute("DROP FUNCTION app.reject_outbox_insert()");}
        var first=store(input(attempt)); store(input(attempt));
        sql.update("UPDATE app.outbox_messages SET envelope=? WHERE message_id=?",new byte[]{123},first.messageId());
        try(var transport=new KafkaOutboxTransport(new OutboxSettings(10000,50,10000,60000,"localhost:1",10000,10000,30000,35000))) {
            new OutboxPublisher(outbox,transport,Duration.ofSeconds(60)).publishOne();
        }
        assertThat(status(first.messageId())).isEqualTo("SUSPENDED"); assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty();
    }
    @Test void kafkaWaitReleasesTransactionConnectionAndRowLock() throws Exception {
        var saved=store(input(UUID.randomUUID()));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var publisher=new OutboxPublisher(outbox,m -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); entered.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return new OutboxTransport.Ack(0,7);
        },Duration.ofSeconds(60));
        try (var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(publisher::publishOne);
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                try (var c=db.connection(); var s=c.createStatement()) {
                    c.setAutoCommit(false); s.execute("SET LOCAL lock_timeout='500ms'");
                    s.executeQuery("SELECT 1 FROM app.outbox_messages WHERE message_id='"+saved.messageId()+"' FOR UPDATE").close(); c.rollback();
                }
                var poolBean=app.getBean(com.zaxxer.hikari.HikariDataSource.class);
                assertThat(poolBean.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally { release.countDown(); }
            assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(status(saved.messageId())).isEqualTo("PUBLISHED");
    }
    @Test void boundedRoundSurvivesDatabaseFailureAndDoesNotSendBeforeClaimCommit() throws Exception {
        var sent=new java.util.concurrent.atomic.AtomicInteger();
        var p=new OutboxPublisher(outbox,m -> { sent.incrementAndGet(); return new OutboxTransport.Ack(0,1); },Duration.ofSeconds(60));
        store(input(UUID.randomUUID())); store(input(UUID.randomUUID()));
        try(var c=db.connection();var s=c.createStatement()) {
            c.setAutoCommit(false);s.execute("LOCK TABLE app.outbox_messages IN ACCESS EXCLUSIVE MODE");
            assertThat(p.poll(50,Duration.ofSeconds(10))).isZero(); assertThat(sent.get()).isZero(); c.rollback();
        }
        assertThat(p.poll(1,Duration.ofSeconds(10))).isEqualTo(1); assertThat(sent.get()).isEqualTo(1);
        assertThat(p.poll(50,Duration.ofSeconds(10))).isEqualTo(1); p.stop();
        store(input(UUID.randomUUID())); assertThat(p.poll(50,Duration.ofSeconds(10))).isZero();
    }
    @Test void actualCommitResponseLossKeepsCommittedCompletionAndDoesNotRepublish() throws Exception {
        var saved=store(input(UUID.randomUUID()));
        var target=java.net.URI.create(db.url().substring(5));
        try(var proxy=new CommitResponseDropProxy(target.getHost(),target.getPort())) {
            String url="jdbc:postgresql://localhost:"+proxy.port()+"/group_purchase?currentSchema=app&sslmode=disable&preferQueryMode=simple&socketTimeout=5";
            var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"group_purchase_app",db.password());
            var proxyStore=new JdbcOutbox(new JdbcTemplate(ds),new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            var publisher=new OutboxPublisher(proxyStore,m -> { proxy.armed.set(true); return new OutboxTransport.Ack(0,123); },Duration.ofSeconds(60));
            assertThatThrownBy(publisher::publishOne).isInstanceOf(org.springframework.transaction.TransactionException.class);
            assertThat(proxy.dropped.get()).isTrue(); assertThat(status(saved.messageId())).isEqualTo("PUBLISHED");
            assertThat(sql.queryForObject("SELECT outcome FROM app.outbox_delivery_attempts WHERE message_id=?",String.class,saved.messageId())).isEqualTo("PUBLISHED");
            assertThat(outbox.claim(Duration.ofSeconds(60))).isEmpty();
        }
    }
    @Test @Timeout(180) void realKafkaReceivesExactlyStoredBytesAndAckLocation() throws Exception {
        try(var broker=new KafkaContainer("apache/kafka:4.1.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false")) {
            broker.start(); String bootstrap=broker.getBootstrapServers();
            var saved=store(input(UUID.randomUUID()));
            try(var transport=new KafkaOutboxTransport(new OutboxSettings(10000,50,10000,15000,bootstrap,250,250,500,1000))) {
                new OutboxPublisher(outbox,transport,Duration.ofSeconds(15)).publishOne();
            }
            assertThat(status(saved.messageId())).isEqualTo("RETRY_WAIT"); due(saved.messageId());
            try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))) {
                admin.createTopics(List.of(new NewTopic("gp.payment.commands.v1",3,(short)1))).all().get(30,TimeUnit.SECONDS);
            }
            byte[] raw=sql.queryForObject("SELECT envelope FROM app.outbox_messages WHERE message_id=?",byte[].class,saved.messageId());
            try(var transport=new KafkaOutboxTransport(new OutboxSettings(10000,50,10000,60000,bootstrap,10000,10000,30000,35000))) {
                assertThat(new OutboxPublisher(outbox,transport,Duration.ofSeconds(60)).publishOne()).isTrue();
            }
            try(var consumer=new KafkaConsumer<String,byte[]>(Map.of("bootstrap.servers",bootstrap,"group.id",key(),
                    "auto.offset.reset","earliest","enable.auto.commit","false","key.deserializer",StringDeserializer.class,"value.deserializer",ByteArrayDeserializer.class))) {
                consumer.subscribe(List.of("gp.payment.commands.v1"));
                ConsumerRecord<String,byte[]> received=null; long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                while(received==null && System.nanoTime()<deadline) for(var r:consumer.poll(Duration.ofMillis(500))) received=r;
                assertThat(received).isNotNull(); assertThat(received.value()).isEqualTo(raw);
                var row=sql.queryForMap("SELECT * FROM app.outbox_messages WHERE message_id=?",saved.messageId());
                assertThat(received.key()).isEqualTo(row.get("message_key")); assertThat(row.get("status")).isEqualTo("PUBLISHED");
                assertThat(row.get("kafka_partition")).isEqualTo(received.partition()); assertThat(row.get("kafka_offset")).isEqualTo(received.offset());
                assertThat(sql.queryForList("SELECT outcome FROM app.outbox_delivery_attempts ORDER BY claim_number",String.class)).containsExactly("RETRY_WAIT","PUBLISHED");
            }
        }
    }

    @Test @Timeout(300) void killedProcessesRecoverStoredClaimedAndAcknowledgedMessages() throws Exception {
        try(var broker=new KafkaContainer("apache/kafka:4.1.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false")) {
            broker.start(); String bootstrap=broker.getBootstrapServers();
            var root=java.nio.file.Files.createTempDirectory("outbox-process-");
            try {
                runProbe("prepare",bootstrap,root);
                for(String boundary:List.of("seed-hold","claim-hold","ack-hold")) {
                    var directory=java.nio.file.Files.createDirectory(root.resolve(boundary));
                    if(!boundary.equals("seed-hold")) runProbe("seed",bootstrap,directory);
                    Process child=probe(boundary,bootstrap,directory);
                    String marker=switch(boundary) { case "seed-hold" -> "stored"; case "claim-hold" -> "claimed"; default -> "acknowledged"; };
                    try { waitMarker(child,directory.resolve(marker+".marker")); }
                    finally { child.destroyForcibly(); assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue(); }
                    UUID id=UUID.fromString(java.nio.file.Files.readString(directory.resolve("message.id")));
                    byte[] raw=sql.queryForObject("SELECT envelope FROM app.outbox_messages WHERE message_id=?",byte[].class,id);
                    UUID previous=sql.queryForObject("SELECT claim_id FROM app.outbox_messages WHERE message_id=?",UUID.class,id);
                    Thread.sleep(3100); runProbe("publish",bootstrap,directory);
                    assertThat(status(id)).isEqualTo("PUBLISHED");
                    assertThat(sql.queryForObject("SELECT envelope FROM app.outbox_messages WHERE message_id=?",byte[].class,id)).isEqualTo(raw);
                    String output=runProbe("receive",bootstrap,directory);
                    assertThat(output).contains(boundary.equals("ack-hold")?"MATCHES 2":"MATCHES 1");
                    var outcomes=sql.queryForList("SELECT outcome FROM app.outbox_delivery_attempts WHERE message_id=? ORDER BY claim_number",String.class,id);
                    assertThat(outcomes).containsExactlyElementsOf(previous==null?List.of("PUBLISHED"):List.of("EXPIRED","PUBLISHED"));
                    if(previous!=null) assertThat(sql.queryForObject("SELECT claim_id FROM app.outbox_delivery_attempts WHERE message_id=? AND outcome='PUBLISHED'",UUID.class,id)).isNotEqualTo(previous);
                    System.out.println("PROCESS_RECOVERY_OK boundary="+boundary+" messageId="+id+" attempts="+outcomes);
                }
                // Kafka ACK succeeds, but the DB rejects completion. No false delivery failure is written.
                var directory=java.nio.file.Files.createDirectory(root.resolve("db-failure"));
                runProbe("seed",bootstrap,directory);
                UUID id=UUID.fromString(java.nio.file.Files.readString(directory.resolve("message.id")));
                sql.execute("CREATE FUNCTION app.reject_outbox_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='PUBLISHED' THEN RAISE EXCEPTION 'isolated completion failure'; END IF; RETURN NEW; END $$");
                sql.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON app.outbox_messages FOR EACH ROW EXECUTE FUNCTION app.reject_outbox_completion()");
                try {
                    assertThat(runProbe("publish-short",bootstrap,directory)).contains("outbox_round_failed");
                    assertThat(status(id)).isEqualTo("IN_FLIGHT");
                }
                finally { sql.execute("DROP TRIGGER reject_completion ON app.outbox_messages");sql.execute("DROP FUNCTION app.reject_outbox_completion()"); }
                Thread.sleep(15100); runProbe("publish",bootstrap,directory);
                assertThat(status(id)).isEqualTo("PUBLISHED"); assertThat(runProbe("receive",bootstrap,directory)).contains("MATCHES 2");
                System.out.println("ACK_DB_FAILURE_RECOVERY_OK messageId="+id);
            } finally {
                try(var paths=java.nio.file.Files.walk(root)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path); }
            }
        }
    }
    static Process probe(String mode,String bootstrap,Path directory) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new ProcessBuilder(java,"-jar",System.getProperty("test.outbox-jar"),mode,db.url(),"group_purchase_app",
                db.secretDirectory.resolve("spring.datasource.password").toString(),bootstrap,directory.toString(),
                mode.equals("publish")?"60000":mode.equals("publish-short")?"15000":"3000")
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
    }
    static String runProbe(String mode,String bootstrap,Path directory) throws Exception {
        Process child=probe(mode,bootstrap,directory);
        try {
            assertThat(child.waitFor(90,TimeUnit.SECONDS)).isTrue();
            String output=java.nio.file.Files.readString(directory.resolve(mode+".log"));
            assertThat(child.exitValue()).withFailMessage(output).isZero(); return output;
        } finally { if(child.isAlive()) {child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);} }
    }
    static void waitMarker(Process process,Path marker) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(!java.nio.file.Files.exists(marker) && process.isAlive() && System.nanoTime()<until) Thread.sleep(100);
        assertThat(java.nio.file.Files.exists(marker)).withFailMessage("Missing marker %s",marker).isTrue();
    }
}
