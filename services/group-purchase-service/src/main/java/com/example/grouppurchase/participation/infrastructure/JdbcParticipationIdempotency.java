package com.example.grouppurchase.participation.infrastructure;

import com.example.grouppurchase.participation.application.ParticipationChange.Receipt;
import com.example.grouppurchase.participation.application.ParticipationIdempotency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcParticipationIdempotency implements ParticipationIdempotency {
    private static final String KEY = " WHERE buyer_id = :buyer AND operation = :operation AND idempotency_key = :key";
    private final JdbcClient jdbc;

    public JdbcParticipationIdempotency(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredResult> claim(Key key, int version, byte[] fingerprint) {
        int inserted = bind("""
                INSERT INTO app.participation_idempotency
                    (buyer_id, operation, idempotency_key, fingerprint_version, fingerprint)
                VALUES (:buyer, :operation, :key, :version, :fingerprint)
                ON CONFLICT (buyer_id, operation, idempotency_key) DO NOTHING
                """, key).param("version", version).param("fingerprint", fingerprint).update();
        if (inserted == 1) return Optional.empty();

        // READ COMMITTED sees the winning transaction's committed receipt in this new statement.
        var stored = bind("SELECT * FROM app.participation_idempotency" + KEY, key)
                .query((row, index) -> new StoredResult(row.getInt("fingerprint_version"), row.getBytes("fingerprint"),
                        new Receipt(row.getObject("participation_id", UUID.class), row.getInt("response_status"),
                                row.getString("response_body"), row.getString("response_location"))))
                .single();
        if (stored.receipt().id() == null) throw new IllegalStateException("Committed key has no result");
        return Optional.of(stored);
    }

    @Override
    public void complete(Key key, Receipt receipt) {
        int updated = bind("""
                UPDATE app.participation_idempotency
                SET participation_id = :id, response_status = :status, response_body = :body,
                    response_location = :location
                """ + KEY + " AND participation_id IS NULL", key)
                .param("id", receipt.id()).param("status", receipt.status())
                .param("body", receipt.body()).param("location", receipt.location()).update();
        if (updated != 1) throw new IllegalStateException("Key was not claimed");
    }

    private JdbcClient.StatementSpec bind(String sql, Key key) {
        return jdbc.sql(sql).param("buyer", key.buyerId()).param("operation", key.operation().name())
                .param("key", key.value());
    }
}
