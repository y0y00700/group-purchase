package com.example.grouppurchase.inboxprobe;

import com.example.grouppurchase.inbox.application.*;
import com.example.grouppurchase.outbox.infrastructure.JdbcOutbox;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Verification-only business state machine. Never registered in the product context. */
public final class ProbeSupport {
    public static final String TYPE="PaymentApprovalResultReported",CANONICAL="gp.payment.results.v1";
    private ProbeSupport() { }
    public static void schema(JdbcTemplate sql) {
        sql.execute("CREATE TABLE IF NOT EXISTS app.inbox_probe_business(namespace text NOT NULL,business_id uuid NOT NULL,amount bigint NOT NULL,business_state text NOT NULL,effect_count int NOT NULL DEFAULT 0,PRIMARY KEY(namespace,business_id))");
    }
    public static byte[] sample(UUID id,UUID business,String state,long amount,Map<String,Object> extra) {
        var payload=new LinkedHashMap<String,Object>(extra); payload.put("state",state); payload.put("amount",amount);
        return JsonMapper.builder().build().writeValueAsBytes(Map.of("schemaVersion",1,"messageType",TYPE,"messageId",id.toString(),
                "correlationId",UUID.randomUUID().toString(),"occurredAt","2026-11-26T00:00:00.000000Z","sequence",1,
                "context",Map.of("paymentAttemptId",business.toString(),"groupPurchaseId",UUID.randomUUID().toString(),"participationId",UUID.randomUUID().toString()),"payload",payload));
    }
    public static IncomingMessageHandler handler(JdbcTemplate sql,JdbcOutbox outbox,String namespace,Path barrier) {
        return new IncomingMessageHandler() {
            @Override public String messageType() { return TYPE; }
            @Override public String topic() { return CANONICAL; }
            @Override public void handle(IncomingMessage m) {
                if(Boolean.TRUE.equals(m.payload().get("fail"))) throw new IllegalStateException("probe retry");
                long amount=((Number)m.payload().get("amount")).longValue(); String state=(String)m.payload().get("state");
                sql.update("INSERT INTO app.inbox_probe_business(namespace,business_id,amount,business_state) VALUES (?,?,?,'NEW') ON CONFLICT DO NOTHING",namespace,m.businessId(),amount);
                var current=sql.queryForMap("SELECT * FROM app.inbox_probe_business WHERE namespace=? AND business_id=? FOR UPDATE",namespace,m.businessId());
                if(((Number)current.get("amount")).longValue()!=amount) throw new MessageRejected("BUSINESS_CONFLICT");
                String previous=(String)current.get("business_state");
                boolean change=previous.equals("NEW") || (previous.equals("PENDING") && state.equals("SUCCESS"));
                if(change) {
                    sql.update("UPDATE app.inbox_probe_business SET business_state=?,effect_count=effect_count+1 WHERE namespace=? AND business_id=?",state,namespace,m.businessId());
                    if(Boolean.TRUE.equals(m.payload().get("afterBusiness"))) throw new IllegalStateException("after business");
                    outbox.store(new Input(Kind.NotificationRequested,m.groupPurchaseId(),m.participationId(),null,null,m.correlationId(),m.messageId(),
                            Map.of("probe",Boolean.TRUE.equals(m.payload().get("largeOutbox"))?"x".repeat(65536):"ok")));
                    if(Boolean.TRUE.equals(m.payload().get("afterOutbox"))) throw new IllegalStateException("after outbox");
                }
                if(barrier!=null) hold(barrier,"before-db-commit");
            }
        };
    }
    public static void hold(Path directory,String name) {
        try {
            Files.createDirectories(directory); Files.writeString(directory.resolve(name+".marker"),"ready");
            System.out.println("BARRIER "+name); System.out.flush();
            new java.util.concurrent.CountDownLatch(1).await();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
