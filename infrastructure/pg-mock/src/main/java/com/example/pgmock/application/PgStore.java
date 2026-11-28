package com.example.pgmock.application;

import com.example.pgmock.domain.*;
import java.util.*;
import java.util.function.Supplier;

public interface PgStore {
    <T> T transaction(Supplier<T> action);
    Scenario lockScenario(String kind,UUID businessId);
    Scenario putScenario(String kind,UUID businessId,Scenario scenario);
    Scenario scenario(String kind,UUID businessId);
    PgOperation byKey(String kind,String key);
    PgOperation byBusiness(String kind,UUID businessId,boolean lock);
    PgOperation byId(UUID operationId,boolean lock);
    PgOperation refundFor(UUID approvalId);
    PgOperation insert(PgCommand command,Scenario scenario,UUID approvalId);
    PgOperation retry(PgOperation operation);
    PgOperation finish(PgOperation operation);
    List<UUID> pending(int limit);
    Map<String,Object> reconciliation(UUID paymentAttemptId,long ledgerCursor,long attemptCursor,int limit);
    double count(String status);
    double oldestPendingSeconds();
}
