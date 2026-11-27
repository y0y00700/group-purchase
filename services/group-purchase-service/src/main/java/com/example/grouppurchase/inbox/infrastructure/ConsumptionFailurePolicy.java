package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.inbox.application.MessageRejected;
import com.example.grouppurchase.infrastructure.messaging.ContractFailure;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.transaction.CannotCreateTransactionException;

public final class ConsumptionFailurePolicy {
    public enum Kind { PERMANENT, RETRYABLE, DATABASE_UNAVAILABLE }
    public record Decision(Kind kind, String code) { }
    private ConsumptionFailurePolicy() { }
    public static Decision classify(Throwable failure) {
        for (Throwable e=failure; e!=null; e=e.getCause()) {
            if (e instanceof MessageRejected rejected) return new Decision(Kind.PERMANENT,rejected.code());
            if (e instanceof ContractFailure contract) return new Decision(Kind.PERMANENT,contract.code().name());
            if (e instanceof CannotCreateTransactionException) return new Decision(Kind.DATABASE_UNAVAILABLE,"DB_ACQUIRE");
            if (e instanceof SQLException sql) {
                String state=sql.getSQLState();
                if (state!=null && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57P") || state.equals("58030")))
                    return new Decision(Kind.DATABASE_UNAVAILABLE,"DB_"+state);
                if (state!=null && (state.equals("40001") || state.equals("40P01") || state.equals("55P03") || state.equals("57014")))
                    return new Decision(Kind.RETRYABLE,"DB_"+state);
            }
        }
        return new Decision(Kind.RETRYABLE,"HANDLER_UNCLASSIFIED");
    }
    public static boolean halt(Decision decision, int chargedAttempts) {
        return decision.kind()==Kind.PERMANENT || chargedAttempts>=5;
    }
    public static Duration retryDelay(int chargedAttempts) {
        long base=1000L << Math.min(3,Math.max(0,chargedAttempts-1));
        return Duration.ofMillis(base+ThreadLocalRandom.current().nextLong(base/5+1));
    }
}
