package com.example.pgmock.interfaces;

import com.example.pgmock.application.*;
import com.example.pgmock.domain.*;
import com.example.pgmock.infrastructure.PgTelemetry;
import java.net.URI;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pg/v1")
public class PgController {
    private final PgStore store;
    private final AcceptApproval approval;
    private final AcceptRefund refund;
    private final CompletePgOperation complete;
    private final PgTelemetry telemetry;
    public PgController(PgStore store,AcceptApproval approval,AcceptRefund refund,CompletePgOperation complete,PgTelemetry telemetry) {
        this.store=store; this.approval=approval; this.refund=refund; this.complete=complete; this.telemetry=telemetry;
    }
    @PostMapping(value="/{resource:approvals|refunds}",consumes="application/json")
    public ResponseEntity<OperationView> post(@PathVariable String resource,@RequestHeader("Idempotency-Key") String key,@RequestBody byte[] body) {
        boolean isApproval=resource.equals("approvals");
        var fields=isApproval?Set.of("paymentAttemptId","amount","currency"):Set.of("paymentAttemptId","refundId","amount","currency");
        var json=PgJson.object(body,fields);
        var payment=PgJson.uuid(PgJson.string(json,"paymentAttemptId"));
        var business=isApproval?payment:PgJson.uuid(PgJson.string(json,"refundId"));
        var command=new PgCommand(isApproval?"APPROVAL":"REFUND",business,payment,PgJson.integer(json,"amount"),PgJson.string(json,"currency"),key);
        Accepted accepted;
        try { accepted=accept(command); }
        catch (DuplicateKeyException race) {
            // Only our named identity constraints are races. Re-enter a fresh transaction to compare canonical fields.
            String constraint=constraint(race);
            if(!Set.of("pg_operation_key","pg_operation_business","pg_one_full_refund").contains(constraint)) throw race;
            accepted=accept(command);
        }
        if(!accepted.firstAcceptance()) telemetry.event("duplicate",command.kind(),accepted.operation().status(),accepted.operation().operationId(),accepted.operation().attempt());
        var operation=complete.complete(accepted.operation().operationId());
        if(accepted.responseDelayMs()>0) {
            try { Thread.sleep(accepted.responseDelayMs()); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new PgFailure(503,"PG_UNAVAILABLE"); }
            operation=store.byId(operation.operationId(),false);
        }
        if(operation.status().equals("RETRYABLE_FAILURE")) throw new PgFailure(503,"PG_TEMPORARILY_UNAVAILABLE");
        if(operation.status().equals("PENDING")) return ResponseEntity.accepted()
            .location(URI.create("/pg/v1/"+resource+"/"+business)).header("Retry-After","1").body(OperationView.from(operation));
        return ResponseEntity.ok(OperationView.from(operation));
    }
    private Accepted accept(PgCommand command) { return command.kind().equals("APPROVAL")?approval.accept(command):refund.accept(command); }
    private static String constraint(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                // Driver stays runtime-only; obtain the constraint from its structured server error, never log the message.
                try {
                    Object detail=cause.getClass().getMethod("getServerErrorMessage").invoke(cause);
                    return (String)detail.getClass().getMethod("getConstraint").invoke(detail);
                } catch(ReflectiveOperationException ignored) { return ""; }
            }
        }
        return "";
    }
    @GetMapping("/{resource:approvals|refunds}/{businessId}")
    public OperationView get(@PathVariable String resource,@PathVariable String businessId) {
        var operation=store.byBusiness(resource.equals("approvals")?"APPROVAL":"REFUND",PgJson.uuid(businessId),false);
        if(operation==null) throw new PgFailure(404,"NOT_FOUND");
        return OperationView.from(operation);
    }
}
