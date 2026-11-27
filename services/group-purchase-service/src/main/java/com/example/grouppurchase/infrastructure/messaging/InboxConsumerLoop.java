package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.config.InboxSettings;
import com.example.grouppurchase.inbox.application.IncomingRecord;
import com.example.grouppurchase.inbox.infrastructure.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.errors.*;
import org.slf4j.LoggerFactory;

/** The poll thread owns all consumer calls except wakeup(). Paused partitions still heartbeat through poll. */
public final class InboxConsumerLoop implements Runnable,AutoCloseable {
    private final InboxSettings settings;
    private final JdbcInbox store;
    private final InboxProcessor processor;
    private final MeterRegistry metrics;
    private final Supplier<Consumer<byte[],byte[]>> consumers;
    private final Supplier<InboxMetadata> metadataFactory;
    private final Map<TopicPartition,JdbcInbox.Partition> owners=new HashMap<>();
    private final Map<TopicPartition,Long> safe=new HashMap<>();
    private final Set<TopicPartition> pending=new LinkedHashSet<>();
    private volatile Consumer<byte[],byte[]> consumer;
    private volatile boolean stopping;
    private volatile String status="DISABLED";
    private volatile int held;
    private Thread thread;
    private long databaseRetryAt,metadataAt,previousPoll;
    private InboxProcessor.DatabaseUncertain uncertain;
    public InboxConsumerLoop(InboxSettings settings,JdbcInbox store,InboxProcessor processor,MeterRegistry metrics) {
        this(settings,store,processor,metrics,()->new KafkaConsumer<>(settings.consumerProperties()),()->InboxMetadata.kafka(settings.bootstrap()));
    }
    public InboxConsumerLoop(InboxSettings settings,JdbcInbox store,InboxProcessor processor,MeterRegistry metrics,
            Supplier<Consumer<byte[],byte[]>> consumers,Supplier<InboxMetadata> metadataFactory) {
        this.settings=settings; this.store=store; this.processor=processor; this.metrics=metrics; this.consumers=consumers; this.metadataFactory=metadataFactory;
        metrics.gauge("inbox.held.partitions",this,x->x.held);
    }
    public synchronized void start() {
        if(settings.enabled() && thread==null) { status="STARTING"; thread=new Thread(this,"inbox-consumer"); thread.start(); }
    }
    public String status() { return status; }
    @Override public void run() {
        try(var c=consumers.get(); var metadata=metadataFactory.get()) {
            consumer=c;
            c.subscribe(List.of(settings.topic()),new ConsumerRebalanceListener() {
                @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) { c.pause(partitions); pending.addAll(partitions); }
                @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) { commitCompleted(partitions); discard(partitions); }
                @Override public void onPartitionsLost(Collection<TopicPartition> partitions) { discard(partitions); }
            });
            while(!stopping) {
                try {
                    long now=System.nanoTime();
                    if(previousPoll!=0) metrics.timer("inbox.poll.interval").record(now-previousPoll,TimeUnit.NANOSECONDS);
                    previousPoll=now;
                    ConsumerRecords<byte[],byte[]> records;
                    try { records=c.poll(Duration.ofMillis(500)); }
                    catch(NoOffsetForPartitionException missing) {
                        // With reset=none even a paused new assignment needs explicit DB preparation.
                        // Never choose earliest/latest or advance a persisted OFFSET_GAP.
                        c.pause(missing.partitions());
                        for(var p:missing.partitions()) if(!owners.containsKey(p)) pending.add(p);
                        records=ConsumerRecords.empty();
                        java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
                    }
                    if(databaseRetryAt!=0) {
                        c.pause(c.assignment());
                        if(now<databaseRetryAt) continue;
                        store.checkDatabase();
                        if(uncertain!=null && uncertain.attempt!=null) {
                            try { store.commonFailure(uncertain.owner,uncertain.attempt,"DB_RESULT_RECONCILED"); }
                            catch(JdbcInbox.OwnershipLost lost) { /* new owner reconciles committed result */ }
                        }
                        uncertain=null; databaseRetryAt=0; pending.addAll(c.assignment()); owners.clear(); safe.clear();
                    }
                    if(now>=metadataAt) { pending.addAll(c.assignment()); c.pause(c.assignment()); metadataAt=now+Duration.ofSeconds(30).toNanos(); }
                    // One partition per poll keeps even assignment preparation bounded.
                    if(!pending.isEmpty()) {
                        var p=pending.iterator().next();
                        if(c.assignment().contains(p)) restorePartition(c,metadata,p);
                        pending.remove(p);
                    }
                    refreshWaiting(c);
                    for(var r:records) {
                        var p=new TopicPartition(r.topic(),r.partition()); var owner=owners.get(p);
                        if(pending.contains(p) || owner==null || c.paused().contains(p) || stopping) continue;
                        var raw=new IncomingRecord(r.topic(),owner.topicId(),r.partition(),r.offset(),r.key(),r.value(),
                                Arrays.stream(r.headers().toArray()).map(h->new IncomingRecord.Header(h.key(),h.value())).toList(),r.timestamp(),r.timestampType().name(),r.leaderEpoch().orElse(null));
                        var result=processor.process(owner,raw);
                        if(result.completed()) safe.merge(p,result.next(),Math::max);
                        else { c.pause(List.of(p)); owners.put(p,store.read(owner.key()).orElseThrow()); }
                    }
                    commitCompleted(c.assignment()); held=c.paused().size(); status="RUNNING";
                } catch(WakeupException e) { if(stopping) break; throw e; }
                catch(InboxProcessor.DatabaseUncertain e) { uncertain=e; pauseDatabase(c); }
                catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) { pauseDatabase(c); }
                catch(JdbcInbox.OwnershipLost e) { c.pause(c.assignment()); pending.addAll(c.assignment()); owners.clear(); safe.clear(); }
                catch(JdbcInbox.PartitionHeld e) { c.pause(c.assignment()); pending.addAll(c.assignment()); }
                catch(org.apache.kafka.clients.consumer.OffsetOutOfRangeException e) {
                    try {
                        for(var p:e.offsetOutOfRangePartitions().keySet()) {
                            var owner=owners.get(p);
                            if(owner!=null) store.transaction(()->{store.gap(owner,"OFFSET_OUT_OF_RANGE"); return null;});
                            c.pause(List.of(p)); safe.remove(p);
                        }
                        metrics.counter("inbox.offset.gaps").increment();
                    } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) { pauseDatabase(c); }
                } catch(AuthenticationException | AuthorizationException | org.apache.kafka.common.config.ConfigException e) { status="CONFIGURATION_ERROR"; log("inbox_configuration_error",e); break; }
                catch(KafkaException e) { status="KAFKA_UNAVAILABLE"; log("inbox_kafka_unavailable",e); java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); }
            }
            commitCompleted(c.assignment());
        } catch(WakeupException e) { if(!stopping) status="FAILED"; }
        catch(AuthenticationException | AuthorizationException | org.apache.kafka.common.config.ConfigException e) { status="CONFIGURATION_ERROR"; log("inbox_configuration_error",e); }
        catch(RuntimeException e) { status="FAILED"; log("inbox_stopped",e); }
        finally { consumer=null; if(stopping) status="STOPPED"; }
    }
    private void restorePartition(Consumer<byte[],byte[]> c,InboxMetadata metadata,TopicPartition p) {
        var key=new JdbcInbox.Key(settings.group(),p.topic(),p.partition(),settings.role());
        var restored=store.restorePartition(key,metadata.range(c,p),settings.bootstrapMode().equals("from-origin"),settings.expectedTopicId(),settings.partitions());
        var owner=restored.partition(); owners.put(p,owner); safe.remove(p);
        if(owner.state().equals("OFFSET_GAP")) { metrics.counter("inbox.offset.gaps").increment(); return; }
        c.seek(p,restored.seek());
        if(restored.restoreCommit()) { safe.put(p,owner.next()); commitCompleted(List.of(p)); }
        if(owner.state().equals("ACTIVE")) c.resume(List.of(p));
    }
    private void refreshWaiting(Consumer<byte[],byte[]> c) {
        for(var entry:new ArrayList<>(owners.entrySet())) {
            if(!c.paused().contains(entry.getKey()) || pending.contains(entry.getKey())) continue;
            var p=store.read(entry.getValue().key()).orElseThrow();
            if(!p.token().equals(entry.getValue().token())) throw new JdbcInbox.OwnershipLost();
            owners.put(entry.getKey(),p);
            if(p.state().equals("RETRY_WAIT") && p.due()) { c.seek(entry.getKey(),p.failedOffset()); c.resume(List.of(entry.getKey())); }
        }
    }
    private void commitCompleted(Collection<TopicPartition> partitions) {
        var c=consumer; if(c==null) return;
        var offsets=new HashMap<TopicPartition,OffsetAndMetadata>();
        for(var p:partitions) if(c.assignment().contains(p) && owners.containsKey(p) && safe.containsKey(p)
                && !owners.get(p).state().equals("OFFSET_GAP")) offsets.put(p,new OffsetAndMetadata(safe.get(p)));
        if(offsets.isEmpty()) return;
        try {
            c.commitSync(offsets,Duration.ofSeconds(5));
            offsets.forEach((p,o)->{ if(Objects.equals(safe.get(p),o.offset())) safe.remove(p); });
        } catch(AuthenticationException | AuthorizationException | org.apache.kafka.common.config.ConfigException fatal) { throw fatal; }
        catch(KafkaException e) { metrics.counter("inbox.commit.failures").increment(); log("inbox_commit_unconfirmed",e); }
    }
    private void discard(Collection<TopicPartition> partitions) {
        partitions.forEach(p->{ owners.remove(p); safe.remove(p); pending.remove(p); });
        if(uncertain!=null && partitions.contains(new TopicPartition(uncertain.owner.key().topic(),uncertain.owner.key().partition()))) uncertain=null;
    }
    private void pauseDatabase(Consumer<byte[],byte[]> c) {
        c.pause(c.assignment()); databaseRetryAt=System.nanoTime()+Duration.ofSeconds(5).toNanos(); status="DB_UNAVAILABLE";
        held=c.assignment().size();
        log("inbox_database_unavailable_unpersisted",null);
    }
    private void log(String event,Exception e) {
        LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("group",settings.group()).addKeyValue("role",settings.role())
                .addKeyValue("errorCode",e==null?"DB_UNAVAILABLE":e.getClass().getSimpleName()).log(event);
    }
    public void stop() { stopping=true; var c=consumer; if(c!=null) c.wakeup(); }
    @Override public void close() {
        stop(); var t=thread;
        if(t!=null) try { t.join(40000); if(t.isAlive()) throw new IllegalStateException("Inbox did not stop within deadline"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
