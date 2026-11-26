package com.example.grouppurchase.outbox.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.apache.kafka.common.errors.*;
import com.example.grouppurchase.infrastructure.messaging.*;
import com.example.grouppurchase.config.OutboxSettings;

class DeliveryFailureTests {
    @Test void knownClassesAndUnknownFailuresHaveExplicitPolicy() {
        for(Exception e:new Exception[]{new org.apache.kafka.common.errors.TimeoutException(),new NetworkException(),
                new java.util.concurrent.TimeoutException(),new InterruptedException(),new ExecutionException(new NotEnoughReplicasException())})
            assertThat(DeliveryFailure.classify(e).retryable()).isTrue();
        for(Exception e:new Exception[]{new AuthenticationException("x"),new AuthorizationException("x"),new RecordTooLargeException(),
                new SerializationException(),new InvalidTopicException(),new IllegalArgumentException(),new IllegalStateException(),
                new ContractFailure(ContractFailure.Code.MALFORMED_JSON,"x")})
            assertThat(DeliveryFailure.classify(e).retryable()).isFalse();
    }
    @Test void jitterSaturatesWithoutOverflowAndTimingsRejectUnsafeLease() {
        for(long n:new long[]{0,1,5,6,63,Long.MAX_VALUE}) for(int i=0;i<50;i++) {
            long base=n>=6?60000:1000L<<n;
            assertThat(DeliveryFailure.delay(n).toMillis()).isBetween(base*80/100,base);
        }
        assertThatThrownBy(() -> new OutboxSettings(10000,50,10000,45000,"localhost:9092",10000,10000,30000,35000)).isInstanceOf(IllegalArgumentException.class);
    }
}
