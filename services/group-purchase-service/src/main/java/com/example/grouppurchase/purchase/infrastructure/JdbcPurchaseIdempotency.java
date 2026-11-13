package com.example.grouppurchase.purchase.infrastructure;

import com.example.grouppurchase.purchase.application.PurchaseChange.Receipt;
import com.example.grouppurchase.purchase.application.PurchaseIdempotency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseIdempotency implements PurchaseIdempotency {
    private static final String KEY = " WHERE member_id = :member AND operation = :operation AND idempotency_key = :key";
    private final JdbcClient jdbc;

    public JdbcPurchaseIdempotency(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredResult> claim(Key key, int version, byte[] fingerprint) {
        int inserted = bind("""
                INSERT INTO app.group_purchase_idempotency
                    (member_id, operation, idempotency_key, fingerprint_version, fingerprint)
                VALUES (:member, :operation, :key, :version, :fingerprint)
                ON CONFLICT (member_id, operation, idempotency_key) DO NOTHING
                """, key).param("version", version).param("fingerprint", fingerprint).update();
        if (inserted == 1) return Optional.empty();

        // READ COMMITTED takes a new snapshot here, after the conflicting insert has committed.
        var stored = bind("SELECT * FROM app.group_purchase_idempotency" + KEY, key)
                .query((row, index) -> new StoredResult(row.getInt("fingerprint_version"), row.getBytes("fingerprint"),
                        new Receipt(row.getObject("purchase_id", UUID.class), row.getInt("response_status"),
                                row.getString("response_body"), row.getString("response_location"))))
                .single();
        if (stored.receipt().id() == null) throw new IllegalStateException("Committed key has no result");
        return Optional.of(stored);
    }

    @Override
    public void complete(Key key, Receipt receipt) {
        int updated = bind("""
                UPDATE app.group_purchase_idempotency
                SET purchase_id = :purchase, response_status = :status, response_body = :body,
                    response_location = :location
                """ + KEY + " AND purchase_id IS NULL", key)
                .param("purchase", receipt.id()).param("status", receipt.status())
                .param("body", receipt.body()).param("location", receipt.location()).update();
        if (updated != 1) throw new IllegalStateException("Key was not claimed");
    }

    private JdbcClient.StatementSpec bind(String sql, Key key) {
        return jdbc.sql(sql).param("member", key.memberId()).param("operation", key.operation().name())
                .param("key", key.value());
    }
}
