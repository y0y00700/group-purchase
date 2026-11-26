package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.inbox.infrastructure.JdbcInbox;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;

public interface InboxMetadata extends AutoCloseable {
    JdbcInbox.Range range(Consumer<byte[],byte[]> consumer,TopicPartition partition);
    @Override default void close() { }
    static InboxMetadata kafka(String bootstrap) {
        return new InboxMetadata() {
            final Admin admin=Admin.create(Map.of("bootstrap.servers",bootstrap,"default.api.timeout.ms",5000,"request.timeout.ms",5000));
            @Override public JdbcInbox.Range range(Consumer<byte[],byte[]> c,TopicPartition p) {
                try {
                    var topic=admin.describeTopics(List.of(p.topic()),new DescribeTopicsOptions().timeoutMs(5000)).allTopicNames().get(5,TimeUnit.SECONDS).get(p.topic());
                    long beginning=c.beginningOffsets(List.of(p),Duration.ofSeconds(5)).get(p),end=c.endOffsets(List.of(p),Duration.ofSeconds(5)).get(p);
                    var committed=c.committed(Set.of(p),Duration.ofSeconds(5)).get(p);
                    return new JdbcInbox.Range(topic.topicId().toString(),topic.partitions().size(),beginning,end,committed==null?null:committed.offset());
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Metadata interrupted",e); }
                catch(java.util.concurrent.ExecutionException e) {
                    if(e.getCause() instanceof org.apache.kafka.common.errors.AuthenticationException fatal) throw fatal;
                    if(e.getCause() instanceof org.apache.kafka.common.errors.AuthorizationException fatal) throw fatal;
                    throw new org.apache.kafka.common.KafkaException("Metadata unavailable",e);
                }
                catch(java.util.concurrent.TimeoutException e) { throw new org.apache.kafka.common.KafkaException("Metadata unavailable",e); }
            }
            @Override public void close() { admin.close(Duration.ofSeconds(5)); }
        };
    }
}
