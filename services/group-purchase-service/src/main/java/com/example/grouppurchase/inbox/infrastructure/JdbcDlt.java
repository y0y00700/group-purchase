package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.inbox.application.IncomingRecord;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcDlt {
    public record Claim(UUID failureId,UUID token,UUID attemptId,int number,Map<String,Object> fields) { }
    private final JdbcTemplate sql;
    private final JdbcInbox inbox;
    public JdbcDlt(JdbcTemplate sql,JdbcInbox inbox) { this.sql=sql; this.inbox=inbox; }
    public Optional<Claim> claim(Duration lease) {
        return inbox.transaction(() -> {
            var candidates=sql.queryForList("""
                    SELECT * FROM app.inbox_failures WHERE recovery_state='UNRESOLVED' AND
                    ((dlt_state IN ('PENDING','RETRY_WAIT') AND dlt_next_at<=clock_timestamp()) OR
                     (dlt_state='IN_FLIGHT' AND dlt_claim_until<=clock_timestamp()))
                    ORDER BY dlt_next_at,first_failed_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """);
            if(candidates.isEmpty()) return Optional.empty();
            var row=candidates.getFirst(); UUID id=(UUID)row.get("failure_id"),token=UUID.randomUUID(),attempt=UUID.randomUUID();
            int number=((Number)row.get("dlt_attempts")).intValue()+1;
            sql.update("UPDATE app.inbox_attempts SET ended_at=clock_timestamp(),outcome='LEASE_EXPIRED',error_code='DLT_ACK_UNKNOWN' WHERE attempt_id=? AND ended_at IS NULL",row.get("dlt_claim_id"));
            // Claim id is also the attempt id: expiry can finish precisely the abandoned send.
            token=attempt;
            sql.update("UPDATE app.inbox_failures SET dlt_state='IN_FLIGHT',dlt_claim_id=?,dlt_claim_until=clock_timestamp()+(? * interval '1 millisecond'),dlt_attempts=? WHERE failure_id=?",
                    token,lease.toMillis(),number,id);
            sql.update("INSERT INTO app.inbox_attempts(attempt_id,consumer_group,consumer_role,topic_id,partition,offset_value,phase,attempt_number,budget_charged) VALUES (?,?,?,?,?,?,'DLT',?,false)",
                    attempt,row.get("consumer_group"),row.get("consumer_role"),row.get("topic_id"),row.get("partition"),row.get("offset_value"),number);
            var document=new LinkedHashMap<String,Object>();
            document.put("dltSchemaVersion",1); document.put("failureId",id.toString());
            for(String name:List.of("consumer_group","consumer_role","topic","topic_id","partition","offset_value","record_timestamp","timestamp_type","leader_epoch","message_id","business_id","error_code","first_failed_at","last_failed_at")) {
                Object value=row.get(name); document.put(switch(name) {
                    case "consumer_group" -> "consumerGroup"; case "consumer_role" -> "consumerRole"; case "topic_id" -> "topicId";
                    case "offset_value" -> "offset"; case "record_timestamp" -> "timestamp"; case "timestamp_type" -> "timestampType";
                    case "leader_epoch" -> "leaderEpoch"; case "message_id" -> "messageId"; case "business_id" -> "businessId";
                    case "error_code" -> "errorCode"; case "first_failed_at" -> "firstFailedAt"; case "last_failed_at" -> "lastFailedAt"; default -> name;
                },value==null || value instanceof Number ? value : value.toString());
            }
            document.put("keyBase64",base64((byte[])row.get("raw_key"))); document.put("valueBase64",base64((byte[])row.get("raw_value")));
            document.put("headers",InboxBytes.headers((byte[])row.get("raw_headers")).stream().map(h -> {
                var item=new LinkedHashMap<String,Object>(); item.put("name",h.name()); item.put("valueBase64",base64(h.value())); return item;
            }).toList());
            document.put("attempts",sql.queryForList("SELECT phase,attempt_number,outcome,error_code,budget_charged FROM app.inbox_attempts WHERE consumer_group=? AND topic_id=? AND partition=? AND offset_value=? ORDER BY started_at DESC LIMIT 20",
                    row.get("consumer_group"),row.get("topic_id"),row.get("partition"),row.get("offset_value")));
            document.put("attemptSummary",sql.queryForMap("SELECT count(*) AS total,count(*) FILTER (WHERE phase='HANDLE' AND budget_charged) AS handle_charged,count(*) FILTER (WHERE phase='DLT') AS dlt FROM app.inbox_attempts WHERE consumer_group=? AND topic_id=? AND partition=? AND offset_value=?",
                    row.get("consumer_group"),row.get("topic_id"),row.get("partition"),row.get("offset_value")));
            return Optional.of(new Claim(id,token,attempt,number,document));
        });
    }
    private static String base64(byte[] bytes) { return bytes==null?null:Base64.getEncoder().encodeToString(bytes); }
    public boolean complete(Claim c,int partition,long offset) { return result(c,"DLT_STORED",partition,offset,null,Duration.ZERO); }
    public boolean fail(Claim c,boolean retryable,String code,Duration delay) { return result(c,retryable?"RETRY_WAIT":"DLT_BLOCKED",null,null,code,delay); }
    private boolean result(Claim c,String state,Integer partition,Long offset,String code,Duration delay) {
        return inbox.transaction(() -> {
            int updated=sql.update("""
                    UPDATE app.inbox_failures SET dlt_state=?,dlt_partition=?,dlt_offset=?,dlt_error=?,dlt_next_at=clock_timestamp()+(? * interval '1 millisecond'),
                    dlt_claim_id=NULL,dlt_claim_until=NULL WHERE failure_id=? AND dlt_claim_id=? AND dlt_state='IN_FLIGHT' AND dlt_claim_until>clock_timestamp()
                    """,state,partition,offset,code,delay.toMillis(),c.failureId(),c.token());
            if(updated==1) inbox.finishAttempt(c.attemptId(),state,code,false);
            return updated==1;
        });
    }
    public long pending() { return sql.queryForObject("SELECT count(*) FROM app.inbox_failures WHERE dlt_state NOT IN ('NOT_REQUIRED','DLT_STORED')",Long.class); }
    public double oldestSeconds() { return sql.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(first_failed_at)),0) FROM app.inbox_failures WHERE dlt_state NOT IN ('NOT_REQUIRED','DLT_STORED')",Double.class); }
}
