package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.inbox.application.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Repository
public class JdbcInbox implements InboxStore {
    public record Key(String group, String topic, int partition, String role) { }
    public record Range(String topicId, int partitionCount, long beginning, long end, Long committed) { }
    public record Partition(Key key, String topicId, UUID token, long next, String state, Long failedOffset, boolean due, long version) { }
    public record Restored(Partition partition, long seek, boolean restoreCommit) { }
    public record Attempt(UUID id, int charged, boolean budgetCharged) { }
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    public JdbcInbox(JdbcTemplate sql, PlatformTransactionManager transactions) {
        this.sql=sql; tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5);
    }
    public <T> T transaction(java.util.function.Supplier<T> work) {
        return tx.execute(s -> { limits(); return work.get(); });
    }
    private void limits() { sql.execute("SET LOCAL statement_timeout='4s'"); sql.execute("SET LOCAL lock_timeout='2s'"); }
    private void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Inbox requires transaction");
    }
    private Partition map(ResultSet rs, Key key) throws SQLException {
        return new Partition(key,rs.getString("topic_id"),rs.getObject("owner_token",UUID.class),rs.getLong("next_offset"),
                rs.getString("state"),rs.getObject("failure_offset",Long.class),rs.getBoolean("due"),rs.getLong("state_version"));
    }
    public Optional<Partition> read(Key key) { return query(key,false).stream().findFirst(); }
    private List<Partition> query(Key key,boolean lock) {
        return sql.query("SELECT *,retry_at IS NULL OR retry_at<=clock_timestamp() AS due FROM app.inbox_partition_states WHERE consumer_group=? AND topic=? AND partition=?"+(lock?" FOR UPDATE":""),
                (rs,n)->map(rs,key),key.group(),key.topic(),key.partition());
    }
    public Partition lock(Partition owner) {
        requireTransaction(); var p=query(owner.key(),true).stream().findFirst().orElseThrow();
        if(!p.token().equals(owner.token())) throw new OwnershipLost();
        return p;
    }
    public static final class OwnershipLost extends RuntimeException { }
    public static final class PartitionHeld extends RuntimeException { }

    /** Metadata was captured outside the DB transaction. No automatic offset reset. */
    public Restored restorePartition(Key key, Range range, boolean bootstrap, String expectedTopicId, int expectedCount) {
        return transaction(() -> {
            UUID token=UUID.randomUUID();
            var rows=query(key,true);
            boolean absent=rows.isEmpty();
            if(absent) {
                boolean identityInUse=sql.queryForObject("SELECT EXISTS(SELECT 1 FROM app.inbox_partition_states WHERE consumer_role=? AND (consumer_group<>? OR topic<>?))",Boolean.class,key.role(),key.group(),key.topic());
                boolean origin=bootstrap && range.beginning()==0 && range.committed()==null
                        && range.topicId().equals(expectedTopicId) && range.partitionCount()==expectedCount && !identityInUse;
                sql.update("""
                        INSERT INTO app.inbox_partition_states(consumer_group,topic,partition,consumer_role,topic_id,partition_count,
                            next_offset,owner_token,state,reason) VALUES (?,?,?,?,?,?,0,?,?,?) ON CONFLICT DO NOTHING
                        """,key.group(),key.topic(),key.partition(),key.role(),range.topicId(),range.partitionCount(),token,
                        origin?"ACTIVE":"OFFSET_GAP",origin?null:"BOOTSTRAP_REJECTED");
            }
            Partition p=query(key,true).getFirst();
            var identity=sql.queryForMap("SELECT consumer_role,partition_count FROM app.inbox_partition_states WHERE consumer_group=? AND topic=? AND partition=?",key.group(),key.topic(),key.partition());
            boolean mismatch=!p.topicId().equals(range.topicId()) || !key.role().equals(identity.get("consumer_role"))
                    || ((Number)identity.get("partition_count")).intValue()!=range.partitionCount()
                    || range.partitionCount()!=expectedCount || !range.topicId().equals(expectedTopicId);
            boolean gap=mismatch || p.next()<range.beginning() || p.next()>range.end()
                    || (range.committed()!=null && range.committed()>p.next())
                    || (p.failedOffset()!=null && p.failedOffset()<range.beginning());
            sql.update("UPDATE app.inbox_partition_states SET owner_token=?,state_version=state_version+1,updated_at=clock_timestamp() WHERE consumer_group=? AND topic=? AND partition=?",
                    token,key.group(),key.topic(),key.partition());
            p=query(key,true).getFirst();
            if(gap) { gap(p,mismatch?"TOPIC_IDENTITY_CONFLICT":"OFFSET_RANGE_CONFLICT"); p=query(key,true).getFirst(); }
            boolean restore=range.committed()==null || range.committed()<range.beginning();
            long seek=restore ? p.next() : range.committed();
            return new Restored(p,seek,restore && !p.state().equals("OFFSET_GAP"));
        });
    }
    public void gap(Partition owner,String reason) {
        lock(owner);
        sql.update("UPDATE app.inbox_partition_states SET state='OFFSET_GAP',retry_at=NULL,reason=?,state_version=state_version+1,updated_at=clock_timestamp() WHERE consumer_group=? AND topic=? AND partition=?",
                reason,owner.key().group(),owner.key().topic(),owner.key().partition());
    }
    public void eligible(Partition p, IncomingRecord r) {
        if(p.state().equals("HALTED") || p.state().equals("OFFSET_GAP") || !p.due()) throw new PartitionHeld();
        if(!p.topicId().equals(r.topicId())) throw new MessageRejected("OFFSET_GAP");
        if(p.failedOffset()!=null && p.failedOffset()!=r.offset()) throw new MessageRejected("OFFSET_GAP");
    }
    public boolean completed(String role,IncomingRecord r,IncomingMessage m) {
        if(m==null) return false;
        var rows=sql.query("SELECT fingerprint FROM app.inbox_processed_messages WHERE consumer_role=? AND message_id=?",(rs,n)->rs.getBytes(1),role,m.messageId());
        return !rows.isEmpty() && Arrays.equals(rows.getFirst(),InboxBytes.fingerprint(r));
    }
    @Override public boolean register(String role, IncomingRecord r, IncomingMessage m) {
        requireTransaction();
        var inserted=sql.query("""
                INSERT INTO app.inbox_processed_messages(consumer_role,message_id,message_type,business_id,correlation_id,sequence,fingerprint,topic_id,partition,offset_value)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (consumer_role,message_id) DO NOTHING RETURNING message_id
                """,(rs,n)->rs.getObject(1,UUID.class),role,m.messageId(),m.type(),m.businessId(),m.correlationId(),m.sequence(),InboxBytes.fingerprint(r),r.topicId(),r.partition(),r.offset());
        if(!inserted.isEmpty()) return true;
        if(!completed(role,r,m)) throw new MessageRejected("MESSAGE_ID_CONFLICT");
        return false;
    }
    public Attempt begin(Partition owner,IncomingRecord r,IncomingMessage m) {
        return transaction(() -> {
            var p=lock(owner);
            if(p.state().equals("HALTED") || p.state().equals("OFFSET_GAP") || !p.due()) throw new PartitionHeld();
            sql.update("""
                    UPDATE app.inbox_attempts SET ended_at=clock_timestamp(),outcome='INTERRUPTED',error_code='PROCESS_INTERRUPTED'
                    WHERE consumer_group=? AND topic_id=? AND partition=? AND offset_value=? AND phase='HANDLE' AND ended_at IS NULL
                    """,p.key().group(),r.topicId(),r.partition(),r.offset());
            int count=sql.queryForObject("SELECT count(*) FROM app.inbox_attempts WHERE consumer_group=? AND topic_id=? AND partition=? AND offset_value=? AND phase='HANDLE' AND budget_charged",
                    Integer.class,p.key().group(),r.topicId(),r.partition(),r.offset());
            boolean done=completed(p.key().role(),r,m);
            if(count>=5 && !done) return new Attempt(null,count,false);
            UUID id=UUID.randomUUID();
            sql.update("INSERT INTO app.inbox_attempts(attempt_id,consumer_group,consumer_role,topic_id,partition,offset_value,phase,attempt_number,budget_charged) VALUES (?,?,?,?,?,?,'HANDLE',?,?)",
                    id,p.key().group(),p.key().role(),r.topicId(),r.partition(),r.offset(),count+1,!done);
            return new Attempt(id,count+(done?0:1),!done);
        });
    }
    public void finishAttempt(UUID id,String outcome,String code,boolean charged) {
        if(id!=null) sql.update("UPDATE app.inbox_attempts SET ended_at=clock_timestamp(),outcome=?,error_code=?,budget_charged=? WHERE attempt_id=? AND ended_at IS NULL",outcome,code,charged,id);
    }
    public long succeed(Partition owner,IncomingRecord r,Attempt attempt) {
        var p=lock(owner); eligible(p,r);
        sql.update("UPDATE app.inbox_failures SET recovery_state='RECOVERED',last_failed_at=clock_timestamp() WHERE consumer_group=? AND topic_id=? AND partition=? AND offset_value=?",
                p.key().group(),r.topicId(),r.partition(),r.offset());
        long next=Math.max(p.next(),Math.addExact(r.offset(),1));
        sql.update("""
                UPDATE app.inbox_partition_states SET next_offset=?,state='ACTIVE',failure_id=NULL,failure_offset=NULL,retry_at=NULL,reason=NULL,
                state_version=state_version+1,updated_at=clock_timestamp() WHERE consumer_group=? AND topic=? AND partition=?
                """,next,p.key().group(),p.key().topic(),p.key().partition());
        finishAttempt(attempt.id(),"SUCCEEDED",null,attempt.budgetCharged()); return next;
    }
    public UUID fail(Partition owner,IncomingRecord r,IncomingMessage m,Attempt attempt,ConsumptionFailurePolicy.Decision d) {
        return transaction(() -> {
            var p=lock(owner);
            if(p.state().equals("HALTED") || p.state().equals("OFFSET_GAP") || !p.due()) throw new PartitionHeld();
            boolean gap=d.code().equals("OFFSET_GAP") || r.offset()<p.next() || !p.topicId().equals(r.topicId());
            boolean halted=gap || ConsumptionFailurePolicy.halt(d,attempt.charged());
            UUID id=UUID.randomUUID();
            var ids=sql.query("""
                    INSERT INTO app.inbox_failures(failure_id,consumer_group,consumer_role,topic,topic_id,partition,offset_value,message_id,business_id,
                        raw_key,raw_value,raw_headers,record_timestamp,timestamp_type,leader_epoch,error_code,dlt_state)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (consumer_group,topic_id,partition,offset_value) DO UPDATE SET last_failed_at=clock_timestamp(),error_code=EXCLUDED.error_code,
                        dlt_state=CASE WHEN app.inbox_failures.dlt_state='NOT_REQUIRED' THEN EXCLUDED.dlt_state ELSE app.inbox_failures.dlt_state END
                    RETURNING failure_id
                    """,(rs,n)->rs.getObject(1,UUID.class),id,p.key().group(),p.key().role(),r.topic(),r.topicId(),r.partition(),r.offset(),m==null?null:m.messageId(),m==null?null:m.businessId(),
                    r.key(),r.value(),InboxBytes.headers(r.headers()),r.timestamp(),r.timestampType(),r.leaderEpoch(),d.code(),halted&&!gap?"PENDING":"NOT_REQUIRED");
            id=ids.getFirst();
            sql.update("""
                    UPDATE app.inbox_partition_states SET state=?,failure_id=?,failure_offset=?,reason=?,
                    retry_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 millisecond') ELSE NULL END,
                    state_version=state_version+1,updated_at=clock_timestamp() WHERE consumer_group=? AND topic=? AND partition=?
                    """,gap?"OFFSET_GAP":halted?"HALTED":"RETRY_WAIT",id,r.offset(),d.code(),!halted,
                    ConsumptionFailurePolicy.retryDelay(attempt.charged()).toMillis(),p.key().group(),p.key().topic(),p.key().partition());
            finishAttempt(attempt.id(),halted?"HALTED":"RETRY_WAIT",d.code(),true); return id;
        });
    }
    public void commonFailure(Partition owner,Attempt attempt,String code) {
        transaction(() -> { lock(owner); finishAttempt(attempt.id(),"DB_UNAVAILABLE",code,false); return null; });
    }
    public void checkDatabase() { sql.queryForObject("SELECT count(*) FROM app.inbox_partition_states WHERE false",Long.class); }
}
