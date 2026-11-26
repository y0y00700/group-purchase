package com.example.grouppurchase.outboxprobe;

import com.example.grouppurchase.config.OutboxSettings;
import com.example.grouppurchase.infrastructure.messaging.*;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage.*;
import com.example.grouppurchase.outbox.infrastructure.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in verification process. No HTTP endpoint or product fault switches. */
public final class OutboxProbe {
    public static void main(String[] args) throws Exception {
        if(args.length != 7) throw new IllegalArgumentException("mode jdbcUrl user passwordFile bootstrap runDirectory leaseMillis");
        String mode=args[0], bootstrap=args[4]; Path directory=Path.of(args[5]); Files.createDirectories(directory);
        var ds=new DriverManagerDataSource(args[1],args[2],Files.readString(Path.of(args[3])).strip());
        var jdbc=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds); var tx=new TransactionTemplate(manager);
        var store=new JdbcOutbox(jdbc,manager);
        Duration lease=Duration.ofMillis(Long.parseLong(args[6]));
        if(mode.equals("prepare")) {
            try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))) {
                for(String topic:List.of("gp.payment.commands.v1","gp.notification.requests.v1")) {
                    try { admin.createTopics(List.of(new NewTopic(topic,3,(short)1))).all().get(30,TimeUnit.SECONDS); }
                    catch(java.util.concurrent.ExecutionException e) { if(!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e; }
                }
            }
        } else if(mode.startsWith("seed")) {
            var saved=tx.execute(s -> store.store(new Input(Kind.PaymentApprovalRequested,UUID.randomUUID(),UUID.randomUUID(),
                    UUID.randomUUID(),null,UUID.randomUUID(),null,Map.of("probe",true))));
            Files.writeString(directory.resolve("message.id"),saved.messageId().toString());
            System.out.println("STORED "+saved.messageId());
            if(mode.equals("seed-hold")) hold(directory,"stored");
        } else if(mode.equals("inspect")) {
            UUID id=UUID.fromString(Files.readString(directory.resolve("message.id")));
            System.out.println("STATE "+jdbc.queryForMap("SELECT message_id,status,claim_id,sequence,claim_count,retry_count,last_error_code,kafka_partition,kafka_offset FROM app.outbox_messages WHERE message_id=?",id));
            System.out.println("ATTEMPTS "+jdbc.queryForList("SELECT claim_id,claim_number,outcome,error_code FROM app.outbox_delivery_attempts WHERE message_id=? ORDER BY claim_number",id));
        } else if(mode.equals("receive")) {
            UUID id=UUID.fromString(Files.readString(directory.resolve("message.id")));
            byte[] expected=jdbc.queryForObject("SELECT envelope FROM app.outbox_messages WHERE message_id=?",byte[].class,id);
            int matches=0;
            try(var consumer=new KafkaConsumer<String,byte[]>(Map.of("bootstrap.servers",bootstrap,"group.id",UUID.randomUUID().toString(),
                    "auto.offset.reset","earliest","enable.auto.commit","false","key.deserializer",StringDeserializer.class,"value.deserializer",ByteArrayDeserializer.class))) {
                var partitions=consumer.partitionsFor("gp.payment.commands.v1").stream()
                        .map(p -> new org.apache.kafka.common.TopicPartition(p.topic(),p.partition())).toList();
                consumer.assign(partitions); consumer.seekToBeginning(partitions);
                var ends=consumer.endOffsets(partitions); long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                boolean done=false;
                while(!done && System.nanoTime()<until) {
                    for(var r:consumer.poll(Duration.ofMillis(300))) {
                        var decoded=new MessageCodec().receive(r.value(),r.topic(),r.key());
                        if(decoded.envelope().get("messageId").asString().equals(id.toString())) {
                            if(!Arrays.equals(expected,r.value())) throw new IllegalStateException("Original changed");
                            matches++; System.out.println("RECEIVE "+id+" partition="+r.partition()+" offset="+r.offset());
                        }
                    }
                    done=true; for(var partition:partitions) if(consumer.position(partition)<ends.get(partition)) done=false;
                }
                if(!done || matches<1) throw new IllegalStateException("No matching message / poll deadline");
            }
            System.out.println("MATCHES "+matches);
        } else {
            try(var kafka=new KafkaOutboxTransport(new OutboxSettings(10000,50,10000,60000,bootstrap,10000,10000,30000,35000))) {
                OutboxTransport transport=m -> {
                    if(mode.equals("claim-hold")) hold(directory,"claimed");
                    var ack=kafka.send(m);
                    if(mode.equals("ack-hold")) hold(directory,"acknowledged");
                    return ack;
                };
                var publisher=new OutboxPublisher(store,transport,lease);
                System.out.println("PROCESSED "+publisher.poll(50,Duration.ofSeconds(10)));
            }
        }
    }
    private static void hold(Path directory,String name) throws Exception {
        Files.writeString(directory.resolve(name+".marker"),"committed");
        System.out.println("PAUSED "+name); System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
