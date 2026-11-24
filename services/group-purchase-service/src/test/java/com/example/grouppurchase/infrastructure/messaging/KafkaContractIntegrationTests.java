package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.kafkaprobe.KafkaProbe;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.kafka.KafkaContainer;
import static org.junit.jupiter.api.Assertions.*;

class KafkaContractIntegrationTests {
    @Test @Timeout(240)
    void realBrokerOrderingGroupsManualCommitReplayAndPoisonBoundary() throws Exception {
        try (var broker = new KafkaContainer("apache/kafka:4.1.2")) {
            broker.start();
            var probe = new KafkaProbe(broker.getBootstrapServers(), UUID.randomUUID().toString());
            try {
                probe.prepare(); probe.smoke(); probe.seed();
                probe.restart(false); probe.restart(false); probe.restart(true);
                probe.retained();
                assertEquals(ContractFailure.Code.MALFORMED_JSON, assertThrows(ContractFailure.class, probe::bad).code());
                assertEquals(ContractFailure.Code.MALFORMED_JSON, assertThrows(ContractFailure.class, probe::bad).code());
            } finally { probe.cleanup(); }
        }
    }
}
