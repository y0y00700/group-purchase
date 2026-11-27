package com.example.grouppurchase.inboxprobe;

import com.example.grouppurchase.config.*;
import com.example.grouppurchase.inbox.application.*;
import com.example.grouppurchase.inbox.infrastructure.*;
import com.example.grouppurchase.infrastructure.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Only verify.inbox.* resources, finite lifetimes, no product recovery API. */
public final class InboxProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=9) throw new IllegalArgumentException("mode jdbcUrl user passwordFile bootstrap topic group directory durationMs");
        String mode=args[0],topic=args[5],group=args[6]; Path directory=Path.of(args[7]);
        if(!topic.matches("verify\\.inbox\\.[a-z0-9.-]+") || !group.matches("verify\\.inbox\\.[a-z0-9.-]+")) throw new IllegalArgumentException("isolated prefix required");
        long duration=Long.parseLong(args[8]); if(duration<1 || duration>240000) throw new IllegalArgumentException("finite duration required");
        var ds=new DriverManagerDataSource(args[1],args[2],Files.readString(Path.of(args[3])).strip());
        var sql=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds); var store=new JdbcInbox(sql,manager);
        Files.createDirectories(directory);
        if(mode.equals("seed") || mode.equals("append")) {
            try(var producer=new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",args[4],"key.serializer",ByteArraySerializer.class,"value.serializer",ByteArraySerializer.class,"acks","all"))) {
                UUID business=UUID.randomUUID(); byte[] key=business.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] bytes=ProbeSupport.sample(UUID.randomUUID(),business,"SUCCESS",100,Map.of());
                if(mode.equals("seed")) {
                    producer.send(new ProducerRecord<>(topic,0,key,bytes)).get(10,TimeUnit.SECONDS);
                    producer.send(new ProducerRecord<>(topic,0,key,bytes)).get(10,TimeUnit.SECONDS);
                    var poison=new ProducerRecord<byte[],byte[]>(topic,1,key,new byte[]{-1,0,1});
                    poison.headers().add("duplicate",null).add("duplicate",new byte[]{0,-1});
                    producer.send(poison).get(10,TimeUnit.SECONDS);
                    producer.send(new ProducerRecord<>(topic,1,key,bytes)).get(10,TimeUnit.SECONDS);
                }
                UUID normal=UUID.randomUUID();
                producer.send(new ProducerRecord<>(topic,2,normal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),ProbeSupport.sample(UUID.randomUUID(),normal,"SUCCESS",100,Map.of()))).get(10,TimeUnit.SECONDS);
                int appended=Files.exists(directory.resolve("appends"))?Integer.parseInt(Files.readString(directory.resolve("appends"))):0;
                if(mode.equals("append")) Files.writeString(directory.resolve("appends"),Integer.toString(appended+1));
            }
            System.out.println("INBOX_PROBE_OK "+mode); return;
        }
        ProbeSupport.schema(sql); var outbox=new com.example.grouppurchase.outbox.infrastructure.JdbcOutbox(sql,manager);
        var handler=ProbeSupport.handler(sql,outbox,group,mode.equals("before-db")?directory:null);
        var decoder=new InboxDecoder(Map.of(topic,ProbeSupport.CANONICAL),List.of(handler));
        var metrics=new SimpleMeterRegistry(); var processor=new InboxProcessor(store,decoder,metrics);
        String topicId;
        try(var admin=Admin.create(Map.of("bootstrap.servers",args[4]))) {
            if(mode.equals("prepare")) {
                admin.createTopics(List.of(new NewTopic(topic,3,(short)1),new NewTopic(topic+".dlt",3,(short)1).configs(Map.of("max.message.bytes","4194304")))).all().get(20,TimeUnit.SECONDS);
            }
            topicId=admin.describeTopics(List.of(topic)).allTopicNames().get(10,TimeUnit.SECONDS).get(topic).topicId().toString();
        }
        var settings=new InboxSettings(true,args[4],topic,group,group+".role","from-origin",topicId,3,20);
        if(mode.equals("prepare")) {
            Files.writeString(directory.resolve("topic.id"),topicId);
        } else if(mode.equals("verify")) {
            int appends=Files.exists(directory.resolve("appends"))?Integer.parseInt(Files.readString(directory.resolve("appends"))):0;
            long expected=2+appends;
            require(sql.queryForObject("SELECT count(*) FROM app.inbox_processed_messages WHERE consumer_role=?",Long.class,settings.role())==expected,"processed messages");
            require(sql.queryForObject("SELECT count(*) FROM app.inbox_probe_business WHERE namespace=?",Long.class,group)==expected,"business effects");
            require(sql.queryForObject("SELECT count(*) FROM app.outbox_messages WHERE causation_id IN (SELECT message_id FROM app.inbox_processed_messages WHERE consumer_role=?)",Long.class,settings.role())==expected,"outbox effects");
            var held=sql.queryForMap("SELECT state,next_offset FROM app.inbox_partition_states WHERE consumer_group=? AND partition=1",group);
            require(held.get("state").equals("HALTED") && ((Number)held.get("next_offset")).longValue()==0,"source remains held");
            UUID failure=sql.queryForObject("SELECT failure_id FROM app.inbox_failures WHERE consumer_group=? AND dlt_state='DLT_STORED'",UUID.class,group);
            try(var admin=Admin.create(Map.of("bootstrap.servers",args[4]))) {
                var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
                require(offsets.get(new TopicPartition(topic,0)).offset()==2 && offsets.get(new TopicPartition(topic,1)).offset()==0 && offsets.get(new TopicPartition(topic,2)).offset()==1+appends,"safe explicit offsets");
            }
            try(var consumer=new KafkaConsumer<byte[],byte[]>(settings.consumerProperties())) {
                var partitions=List.of(new TopicPartition(topic+".dlt",0),new TopicPartition(topic+".dlt",1),new TopicPartition(topic+".dlt",2));
                consumer.assign(partitions);consumer.seekToBeginning(partitions);boolean found=false;long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
                while(!found && System.nanoTime()<until) for(var r:consumer.poll(Duration.ofMillis(200))) {
                    var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.value());
                    if(node.get("failureId").asString().equals(failure.toString())) {
                        require(Arrays.equals(Base64.getDecoder().decode(node.get("valueBase64").asString()),new byte[]{-1,0,1}),"DLT original");
                        require(node.get("headers").size()==2 && node.get("headers").get(0).get("valueBase64").isNull(),"DLT ordered nullable headers");found=true;
                    }
                }
                require(found,"DLT record");
            }
            System.out.println("INBOX_STATE_OK processed="+expected+" business="+expected+" outbox="+expected+" held=1 failureId="+failure);
        } else if(mode.startsWith("dlt-")) {
            try(var kafka=new KafkaDltTransport(new OutboxSettings(10000,50,10000,60000,args[4],10000,10000,30000,35000),topic+".dlt")) {
                DltTransport transport=(id,raw)-> {
                    if(mode.equals("dlt-before")) ProbeSupport.hold(directory,"before-dlt-send");
                    var ack=kafka.send(id,raw);
                    if(mode.equals("dlt-after")) ProbeSupport.hold(directory,"after-dlt-ack");
                    return ack;
                };
                new DltPublisher(new JdbcDlt(sql,store),transport,Duration.ofSeconds(3)).publishOne();
            }
        } else if(mode.equals("loop")) {
            var loop=new InboxConsumerLoop(settings,store,processor,metrics);
            try(var transport=new KafkaDltTransport(new OutboxSettings(10000,50,10000,60000,args[4],10000,10000,30000,35000),topic+".dlt")) {
                var dlt=new DltPublisher(new JdbcDlt(sql,store),transport,Duration.ofSeconds(60));
                var executor=Executors.newSingleThreadScheduledExecutor();
                executor.scheduleWithFixedDelay(()->{try{dlt.publishOne();}catch(RuntimeException e){System.out.println("DLT_WAIT "+e.getClass().getSimpleName());}},0,1,TimeUnit.SECONDS);
                Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
                loop.start();
                try { Thread.sleep(duration); } finally { loop.close(); executor.shutdown(); executor.awaitTermination(50,TimeUnit.SECONDS); }
                System.out.println("LOOP "+loop.status());
            }
        } else {
            try(var c=new KafkaConsumer<byte[],byte[]>(settings.consumerProperties()); var metadata=InboxMetadata.kafka(args[4])) {
                var partition=new TopicPartition(topic,0); c.assign(List.of(partition));
                var restored=store.restorePartition(new JdbcInbox.Key(group,topic,0,settings.role()),metadata.range(c,partition),true,topicId,3);
                c.seek(partition,restored.seek()); long until=System.nanoTime()+Duration.ofMillis(duration).toNanos();
                boolean handled=false;
                if(restored.restoreCommit() && restored.partition().next()>0) {
                    c.commitSync(Map.of(partition,new OffsetAndMetadata(restored.partition().next())),Duration.ofSeconds(5));
                    handled=true;
                }
                while(!handled && System.nanoTime()<until) for(var r:c.poll(Duration.ofMillis(100))) {
                    var raw=new IncomingRecord(topic,topicId,0,r.offset(),r.key(),r.value(),Arrays.stream(r.headers().toArray()).map(h->new IncomingRecord.Header(h.key(),h.value())).toList(),r.timestamp(),r.timestampType().name(),r.leaderEpoch().orElse(null));
                    var result=processor.process(restored.partition(),raw);
                    if(mode.equals("after-db")) ProbeSupport.hold(directory,"after-db-commit");
                    if(result.completed()) c.commitSync(Map.of(partition,new OffsetAndMetadata(result.next())),Duration.ofSeconds(5));
                    handled=true; break;
                }
                if(!handled) throw new IllegalStateException("No record within deadline");
            }
        }
        System.out.println("INBOX_PROBE_OK "+mode);
    }
    private static void require(boolean value,String detail) { if(!value) throw new IllegalStateException("INBOX_CHECK_FAILED "+detail); }
}
