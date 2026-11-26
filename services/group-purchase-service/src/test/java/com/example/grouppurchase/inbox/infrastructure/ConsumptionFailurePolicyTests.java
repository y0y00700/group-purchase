package com.example.grouppurchase.inbox.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ConsumptionFailurePolicyTests {
    @Test void exactSqlStatesAndUnknownErrors() {
        for(String state:new String[]{"08006","57P01","53300"}) assertThat(ConsumptionFailurePolicy.classify(new RuntimeException(new SQLException("hidden",state))).kind()).isEqualTo(ConsumptionFailurePolicy.Kind.DATABASE_UNAVAILABLE);
        for(String state:new String[]{"40001","40P01","55P03","57014","23505"}) assertThat(ConsumptionFailurePolicy.classify(new SQLException("hidden",state)).kind()).isEqualTo(ConsumptionFailurePolicy.Kind.RETRYABLE);
        assertThat(ConsumptionFailurePolicy.classify(new IllegalStateException()).kind()).isEqualTo(ConsumptionFailurePolicy.Kind.RETRYABLE);
    }
    @Test void fiveAttemptsAndBoundedPositiveJitter() {
        var retry=new ConsumptionFailurePolicy.Decision(ConsumptionFailurePolicy.Kind.RETRYABLE,"test");
        assertThat(ConsumptionFailurePolicy.halt(retry,4)).isFalse(); assertThat(ConsumptionFailurePolicy.halt(retry,5)).isTrue();
        for(int n=1;n<=4;n++) for(int sample=0;sample<100;sample++) assertThat(ConsumptionFailurePolicy.retryDelay(n).toMillis()).isBetween(1000L<<(n-1),(1000L<<(n-1))*120/100);
    }
}
