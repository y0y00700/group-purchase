package com.example.grouppurchase.outbox.infrastructure;

import com.example.grouppurchase.infrastructure.messaging.MessageCodec;
import com.example.grouppurchase.infrastructure.messaging.MessageKind;
import com.example.grouppurchase.outbox.application.StoreOutgoingMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcOutbox implements StoreOutgoingMessage {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JdbcOutbox.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate shortTransaction;
    private final MessageCodec codec = new MessageCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    public record Claim(UUID messageId, UUID claimId, String topic, String key, byte[] raw,
            long retryCount, Long sequence, UUID paymentAttemptId) {
        public Claim { raw = raw.clone(); }
        @Override public byte[] raw() { return raw.clone(); }
    }

    public JdbcOutbox(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        shortTransaction = new TransactionTemplate(transactions);
        shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shortTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        shortTransaction.setTimeout(5);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Stored store(Input input) {
         if (!TransactionSynchronizationManager.isActualTransactionActive()) {
             throw new IllegalStateException("Outbox requires a business transaction");
         }
         limits();
         Objects.requireNonNull(input.kind());
         Objects.requireNonNull(input.payload());
         boolean payment = input.kind() != Kind.NotificationRequested;
         if (!payment && input.paymentAttemptId() != null) throw new IllegalArgumentException("Notification has no stream");
         Long sequence = null;
         if (payment) {
             Objects.requireNonNull(input.paymentAttemptId());
             jdbc.update("INSERT INTO app.outbox_streams(direction,payment_attempt_id) VALUES ('PAYMENT_COMMANDS',?) ON CONFLICT DO NOTHING",
                     input.paymentAttemptId());
             List<Long> allocated = jdbc.query("""
                     UPDATE app.outbox_streams SET last_sequence = last_sequence + 1
                     WHERE direction='PAYMENT_COMMANDS' AND payment_attempt_id=? AND last_sequence < 9223372036854775807
                     RETURNING last_sequence
                     """, (rs, n) -> rs.getLong(1), input.paymentAttemptId());
             if (allocated.isEmpty()) throw new IllegalStateException("Outbox sequence exhausted");
             sequence = allocated.getFirst();
         }
         UUID id = UUID.randomUUID();
         Instant now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
         var envelope = json.createObjectNode();
         envelope.put("schemaVersion", 1).put("messageType", input.kind().name())
                 .put("messageId", id.toString()).put("correlationId", Objects.requireNonNull(input.correlationId()).toString())
                 .put("occurredAt", new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(now));
         if (input.causationId() != null) envelope.put("causationId", input.causationId().toString());
         var context = envelope.putObject("context");
         context.put("groupPurchaseId", Objects.requireNonNull(input.groupPurchaseId()).toString());
         if (input.participationId() != null) context.put("participationId", input.participationId().toString());
         if (payment) context.put("paymentAttemptId", input.paymentAttemptId().toString());
         if (input.refundId() != null) context.put("refundId", input.refundId().toString());
         if (sequence != null) envelope.put("sequence", sequence);
         envelope.set("payload", json.valueToTree(input.payload()));
         byte[] raw = codec.encode(envelope);
         String topic = MessageKind.valueOf(input.kind().name()).topic();
         String key = (payment ? input.paymentAttemptId() : input.groupPurchaseId()).toString();
         var decoded = codec.receive(raw, topic, key);
         if (!decoded.envelope().get("messageId").asString().equals(id.toString())
                 || (payment && decoded.envelope().get("sequence").longValue() != sequence)) {
             throw new IllegalStateException("Outbox metadata mismatch");
         }
         jdbc.update("""
                 INSERT INTO app.outbox_messages(message_id,message_type,topic,message_key,group_purchase_id,
                     participation_id,correlation_id,causation_id,direction,payment_attempt_id,sequence,envelope,created_at)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                 """, id, input.kind().name(), topic, key, input.groupPurchaseId(), input.participationId(),
                 input.correlationId(), input.causationId(), payment ? "PAYMENT_COMMANDS" : null,
                 input.paymentAttemptId(), sequence, raw, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
         return new Stored(id, sequence);
    }

    public Optional<Claim> claim(Duration lease) {
         return shortTransaction.execute(tx -> {
             limits();
             var candidates = jdbc.query("""
                     WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now)
                     SELECT m.* FROM app.outbox_messages m CROSS JOIN t
                     WHERE ((m.status IN ('READY','RETRY_WAIT') AND m.next_attempt_at <= t.now)
                         OR (m.status='IN_FLIGHT' AND m.claim_until <= t.now))
                       AND NOT EXISTS (SELECT 1 FROM app.outbox_messages earlier
                         WHERE earlier.direction=m.direction AND earlier.payment_attempt_id=m.payment_attempt_id
                           AND earlier.sequence < m.sequence AND earlier.status <> 'PUBLISHED')
                     ORDER BY m.next_attempt_at,m.created_at,m.message_id
                     LIMIT 1 FOR UPDATE OF m SKIP LOCKED
                     """, (rs, n) -> new Claim(rs.getObject("message_id", UUID.class), rs.getObject("claim_id", UUID.class),
                     rs.getString("topic"), rs.getString("message_key"), rs.getBytes("envelope"), rs.getLong("retry_count"),
                     (Long) rs.getObject("sequence"), rs.getObject("payment_attempt_id", UUID.class)));
             if (candidates.isEmpty()) return Optional.empty();
             var previous = candidates.getFirst();
             UUID claimId = UUID.randomUUID();
             jdbc.update("""
                     UPDATE app.outbox_delivery_attempts SET outcome='EXPIRED',finished_at=clock_timestamp(),error_code='LEASE_EXPIRED'
                     WHERE claim_id=? AND outcome='IN_FLIGHT'
                     """, previous.claimId());
             jdbc.update("""
                     UPDATE app.outbox_messages SET status='IN_FLIGHT',claim_id=?,
                         claim_until=clock_timestamp() + (? * interval '1 millisecond'),claim_count=claim_count+1
                     WHERE message_id=?
                     """, claimId, lease.toMillis(), previous.messageId());
             jdbc.update("""
                     INSERT INTO app.outbox_delivery_attempts(claim_id,message_id,claim_number,started_at,claim_until,outcome)
                     SELECT claim_id,message_id,claim_count,claim_until - (? * interval '1 millisecond'),claim_until,'IN_FLIGHT'
                     FROM app.outbox_messages WHERE message_id=?
                     """, lease.toMillis(), previous.messageId());
             return Optional.of(new Claim(previous.messageId(), claimId, previous.topic(), previous.key(), previous.raw(),
                     previous.retryCount(), previous.sequence(), previous.paymentAttemptId()));
         });
    }

    public boolean complete(Claim claim, int partition, long offset) {
         return shortTransaction.execute(tx -> {
             limits();
             lockResult(claim);
             int changed = jdbc.update("""
                     WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now)
                     UPDATE app.outbox_messages SET status='PUBLISHED',published_at=t.now,kafka_partition=?,kafka_offset=?,
                         claim_id=NULL,claim_until=NULL FROM t
                     WHERE message_id=? AND status='IN_FLIGHT' AND claim_id=? AND claim_until > t.now
                     """, partition, offset, claim.messageId(), claim.claimId());
             if (changed == 1) finishAttempt(claim, "PUBLISHED", null, partition, offset);
             return changed == 1;
         });
    }

    public boolean fail(Claim claim, boolean retryable, String code, Duration delay) {
         return shortTransaction.execute(tx -> {
             limits();
             lockResult(claim);
             String status = retryable ? "RETRY_WAIT" : "SUSPENDED";
             int changed = jdbc.update("""
                     WITH t AS MATERIALIZED (SELECT clock_timestamp() AS now)
                     UPDATE app.outbox_messages SET status=?,next_attempt_at=t.now + (? * interval '1 millisecond'),
                         retry_count=retry_count + ?,first_failure_at=COALESCE(first_failure_at,t.now),last_failure_at=t.now,
                         last_error_code=?,claim_id=NULL,claim_until=NULL FROM t
                     WHERE message_id=? AND status='IN_FLIGHT' AND claim_id=? AND claim_until > t.now
                     """, status, delay.toMillis(), retryable ? 1 : 0, code, claim.messageId(), claim.claimId());
             if (changed == 1) {
                 finishAttempt(claim, status, code, null, null);
                 LOG.atWarn().addKeyValue("messageId", claim.messageId()).addKeyValue("paymentAttemptId", claim.paymentAttemptId())
                         .addKeyValue("sequence", claim.sequence()).addKeyValue("claimId", claim.claimId()).addKeyValue("errorCode", code)
                         .addKeyValue("nextAttemptAt", jdbc.queryForObject("SELECT next_attempt_at FROM app.outbox_messages WHERE message_id=?",
                                 OffsetDateTime.class, claim.messageId())).log("outbox_failure_transition_pending_commit");
             }
             return changed == 1;
         });
    }

    private void finishAttempt(Claim claim, String outcome, String code, Integer partition, Long offset) {
        int changed = jdbc.update("""
                UPDATE app.outbox_delivery_attempts SET outcome=?,finished_at=clock_timestamp(),error_code=?,kafka_partition=?,kafka_offset=?
                WHERE claim_id=? AND message_id=? AND outcome='IN_FLIGHT'
                """, outcome, code, partition, offset, claim.claimId(), claim.messageId());
        if (changed != 1) throw new IllegalStateException("Missing active delivery attempt");
    }

    private void lockResult(Claim claim) {
        // Evaluate the deadline only after any row-lock wait, never using a pre-wait timestamp.
        jdbc.query("SELECT message_id FROM app.outbox_messages WHERE message_id=? FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), claim.messageId());
    }

    private void limits() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='4s'");
    }
}
