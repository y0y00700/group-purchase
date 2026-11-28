package com.example.pgmock.infrastructure;

import com.example.pgmock.application.PgStore;
import com.example.pgmock.application.OperationView;
import com.example.pgmock.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPgStore implements PgStore {
    private static final String MERCHANT="local-merchant";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PgTelemetry telemetry;
    public JdbcPgStore(JdbcTemplate jdbc,PlatformTransactionManager manager,PgTelemetry telemetry) {
        this.jdbc=jdbc; this.telemetry=telemetry; tx=new TransactionTemplate(manager); tx.setTimeout(2);
    }
    public <T> T transaction(Supplier<T> action) {
        return tx.execute(status->{ jdbc.execute("SET LOCAL lock_timeout='1s'"); return action.get(); });
    }
    private static Instant time(ResultSet r,String column) throws SQLException {
        Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
    }
    private static final RowMapper<PgOperation> OP=(r,n)->new PgOperation(
        r.getObject("operation_id",UUID.class),r.getString("kind"),r.getObject("business_id",UUID.class),
        r.getObject("payment_attempt_id",UUID.class),r.getObject("approval_operation_id",UUID.class),r.getLong("amount"),
        r.getString("currency"),r.getString("idempotency_key"),r.getString("status"),r.getInt("attempt"),r.getInt("remaining_failures"),
        r.getLong("processing_delay_ms"),r.getString("approval_decision"),time(r,"accepted_at"),time(r,"ready_at"),time(r,"completed_at"),r.getString("failure_code"));
    private static final RowMapper<Scenario> SC=(r,n)->new Scenario(r.getLong("processing_delay_ms"),r.getLong("first_response_delay_ms"),
        r.getString("approval_decision"),r.getInt("transient_failures"),r.getObject("operation_id",UUID.class));
    private PgOperation one(String sql,Object... values) { return jdbc.query(sql,OP,values).stream().findFirst().orElse(null); }
    public Scenario lockScenario(String kind,UUID businessId) {
        jdbc.update("INSERT INTO pg_scenario(merchant_id,kind,business_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",MERCHANT,kind,businessId);
        return jdbc.queryForObject("SELECT * FROM pg_scenario WHERE merchant_id=? AND kind=? AND business_id=? FOR UPDATE",SC,MERCHANT,kind,businessId);
    }
    public Scenario putScenario(String kind,UUID id,Scenario desired) {
        desired.validate(kind);
        return transaction(()-> {
            Scenario existing=lockScenario(kind,id);
            if (existing.operationId()!=null && !existing.same(desired)) throw PgFailure.conflict("SCENARIO_ALREADY_BOUND");
            jdbc.update("UPDATE pg_scenario SET processing_delay_ms=?,first_response_delay_ms=?,approval_decision=?,transient_failures=? WHERE merchant_id=? AND kind=? AND business_id=?",
                desired.processingDelayMs(),desired.firstResponseDelayMs(),desired.approvalDecision(),desired.transientFailures(),MERCHANT,kind,id);
            committed(()->telemetry.event("scenario_control",kind,existing.operationId()==null?"UNBOUND":"BOUND",existing.operationId(),0));
            return scenario(kind,id);
        });
    }
    public Scenario scenario(String kind,UUID id) {
        var rows=jdbc.query("SELECT * FROM pg_scenario WHERE merchant_id=? AND kind=? AND business_id=?",SC,MERCHANT,kind,id);
        if(rows.isEmpty()) throw new PgFailure(404,"NOT_FOUND"); return rows.getFirst();
    }
    public PgOperation byKey(String kind,String key) { return one("SELECT * FROM pg_operation WHERE merchant_id=? AND kind=? AND idempotency_key=?",MERCHANT,kind,key); }
    public PgOperation byBusiness(String kind,UUID id,boolean lock) { return one("SELECT * FROM pg_operation WHERE merchant_id=? AND kind=? AND business_id=?"+(lock?" FOR UPDATE":""),MERCHANT,kind,id); }
    public PgOperation byId(UUID id,boolean lock) { return one("SELECT * FROM pg_operation WHERE merchant_id=? AND operation_id=?"+(lock?" FOR UPDATE":""),MERCHANT,id); }
    public PgOperation refundFor(UUID approvalId) { return one("SELECT * FROM pg_operation WHERE merchant_id=? AND approval_operation_id=?",MERCHANT,approvalId); }
    public PgOperation insert(PgCommand c,Scenario s,UUID approvalId) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            WITH t AS (SELECT clock_timestamp() AS at)
            INSERT INTO pg_operation(operation_id,merchant_id,kind,business_id,payment_attempt_id,approval_operation_id,approval_kind,
                amount,currency,idempotency_key,status,attempt,remaining_failures,processing_delay_ms,first_response_delay_ms,approval_decision,accepted_at,ready_at)
            SELECT ?,?,?,?,?,?,?,?,?,?,'PENDING',1,?,?,?,?,at,at+(? * interval '1 millisecond') FROM t
            """,id,MERCHANT,c.kind(),c.businessId(),c.paymentAttemptId(),approvalId,approvalId==null?null:"APPROVAL",
            c.amount(),c.currency(),c.key(),s.transientFailures(),s.processingDelayMs(),s.firstResponseDelayMs(),s.approvalDecision(),s.processingDelayMs());
        jdbc.update("UPDATE pg_scenario SET operation_id=? WHERE merchant_id=? AND kind=? AND business_id=?",id,MERCHANT,c.kind(),c.businessId());
        newAttempt(id);
        committed(()->telemetry.event("accepted",c.kind(),"PENDING",id,1));
        committed(()->telemetry.event("scenario_bound",c.kind(),"BOUND",id,1));
        return byId(id,false);
    }
    private void newAttempt(UUID id) {
        jdbc.update("""
            INSERT INTO pg_execution_attempt(operation_id,attempt,accepted_at,ready_at,result)
            SELECT operation_id,attempt,clock_timestamp(),ready_at,'PENDING' FROM pg_operation WHERE operation_id=?
            """,id);
    }
    public PgOperation retry(PgOperation op) {
        jdbc.update("""
            UPDATE pg_operation SET status='PENDING',attempt=attempt+1,failure_code=NULL,
                ready_at=clock_timestamp()+(processing_delay_ms * interval '1 millisecond')
            WHERE operation_id=? AND status='RETRYABLE_FAILURE'
            """,op.operationId());
        newAttempt(op.operationId());
        committed(()->telemetry.event("retry_accepted",op.kind(),"PENDING",op.operationId(),op.attempt()+1));
        return byId(op.operationId(),false);
    }
    public PgOperation finish(PgOperation op) {
        long started=System.nanoTime();
        // Lock already held. DB time is sampled after lock acquisition, immediately before the effect.
        Instant at=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
        if (op.readyAt().isAfter(at)) return op;
        boolean temporary=op.remainingFailures()>0;
        String result=temporary?"RETRYABLE_FAILURE":op.kind().equals("APPROVAL") && op.approvalDecision().equals("DECLINE")?"DECLINED":"SUCCEEDED";
        String code=temporary?"PG_TEMPORARILY_UNAVAILABLE":result.equals("DECLINED")?"APPROVAL_DECLINED":null;
        Timestamp timestamp=Timestamp.from(at);
        jdbc.update("UPDATE pg_operation SET status=?,remaining_failures=?,completed_at=?,failure_code=? WHERE operation_id=?",
            result,temporary?op.remainingFailures()-1:op.remainingFailures(),result.equals("SUCCEEDED")?timestamp:null,code,op.operationId());
        if (result.equals("SUCCEEDED")) jdbc.update("INSERT INTO pg_ledger(operation_id,amount,kind,occurred_at) VALUES (?,?,?,?)",op.operationId(),op.amount(),op.kind(),timestamp);
        jdbc.update("UPDATE pg_execution_attempt SET result=?,failure_code=?,finished_at=? WHERE operation_id=? AND attempt=?",
            result,code,timestamp,op.operationId(),op.attempt());
        committed(()->telemetry.completed(op,result,System.nanoTime()-started));
        return byId(op.operationId(),false);
    }
    private void committed(Runnable action) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() { action.run(); } });
    }
    public List<UUID> pending(int limit) {
        return jdbc.query("SELECT operation_id FROM pg_operation WHERE merchant_id=? AND status='PENDING' AND ready_at<=clock_timestamp() ORDER BY ready_at,operation_id LIMIT ?",
            (r,n)->r.getObject(1,UUID.class),MERCHANT,limit);
    }
    public Map<String,Object> reconciliation(UUID paymentId,long ledgerCursor,long attemptCursor,int limit) {
        // Each area is bounded independently; at most one approval and one full refund exist.
        var operations=jdbc.query("SELECT * FROM pg_operation WHERE merchant_id=? AND payment_attempt_id=? ORDER BY kind",OP,MERCHANT,paymentId);
        var ledger=jdbc.queryForList("""
            SELECT l.* FROM pg_ledger l JOIN pg_operation o USING(operation_id)
            WHERE o.merchant_id=? AND o.payment_attempt_id=? AND l.sequence>? ORDER BY l.sequence LIMIT ?
            """,MERCHANT,paymentId,ledgerCursor,limit);
        var attempts=jdbc.queryForList("""
            SELECT a.* FROM pg_execution_attempt a JOIN pg_operation o USING(operation_id)
            WHERE o.merchant_id=? AND o.payment_attempt_id=? AND a.sequence>? ORDER BY a.sequence LIMIT ?
            """,MERCHANT,paymentId,attemptCursor,limit);
        var totals=jdbc.queryForList("""
            SELECT l.kind,count(*) AS count,sum(l.amount::numeric) AS amount FROM pg_ledger l JOIN pg_operation o USING(operation_id)
            WHERE o.merchant_id=? AND o.payment_attempt_id=? GROUP BY l.kind ORDER BY l.kind
            """,MERCHANT,paymentId);
        return Map.of("operations",operations.stream().map(OperationView::from).toList(),"ledger",ledger,"attempts",attempts,"totals",totals,
            "nextLedgerSequence",ledger.isEmpty()?ledgerCursor:ledger.getLast().get("sequence"),
            "nextAttemptSequence",attempts.isEmpty()?attemptCursor:attempts.getLast().get("sequence"));
    }
    public double count(String status) { return jdbc.queryForObject("SELECT count(*) FROM pg_operation WHERE status=?",Long.class,status); }
    public double oldestPendingSeconds() {
        return jdbc.queryForObject("SELECT coalesce(greatest(0,extract(epoch FROM clock_timestamp()-min(accepted_at))),0) FROM pg_operation WHERE status='PENDING'",Double.class);
    }
}
