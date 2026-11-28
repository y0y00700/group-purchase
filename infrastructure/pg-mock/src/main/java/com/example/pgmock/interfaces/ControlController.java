package com.example.pgmock.interfaces;

import com.example.pgmock.application.PgStore;
import com.example.pgmock.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@ConditionalOnProperty(name="app.mock-control.enabled",havingValue="true")
@RequestMapping("/__admin/v1")
public class ControlController {
    private final PgStore store;
    public ControlController(PgStore store) { this.store=store; }
    private static String kind(String value) { if(!Set.of("APPROVAL","REFUND").contains(value)) throw new PgFailure(400,"INVALID_REQUEST"); return value; }
    @PutMapping(value="/scenarios/{kind}/{businessId}",consumes="application/json")
    public Scenario put(@PathVariable String kind,@PathVariable String businessId,@RequestBody byte[] body) {
        var node=PgJson.object(body,Set.of("processingDelayMs","firstResponseDelayMs","approvalDecision","transientFailures"));
        long failures=PgJson.integer(node,"transientFailures");
        if(failures<0 || failures>3) throw new PgFailure(400,"INVALID_REQUEST");
        return store.putScenario(kind(kind),PgJson.uuid(businessId),new Scenario(PgJson.integer(node,"processingDelayMs"),
            PgJson.integer(node,"firstResponseDelayMs"),PgJson.string(node,"approvalDecision"),(int)failures,null));
    }
    @GetMapping("/scenarios/{kind}/{businessId}")
    public Scenario get(@PathVariable String kind,@PathVariable String businessId) { return store.scenario(kind(kind),PgJson.uuid(businessId)); }
    @GetMapping("/transactions")
    public Map<String,Object> transactions(@RequestParam String paymentAttemptId,@RequestParam(defaultValue="0") long afterSequence,
            @RequestParam(defaultValue="0") long afterAttemptSequence,@RequestParam(defaultValue="50") int limit) {
        if(afterSequence<0 || afterAttemptSequence<0 || limit<1 || limit>100) throw new PgFailure(400,"INVALID_REQUEST");
        return store.reconciliation(PgJson.uuid(paymentAttemptId),afterSequence,afterAttemptSequence,limit);
    }
}
