package com.example.grouppurchase.participation.infrastructure;

import com.example.grouppurchase.participation.domain.Participation;
import com.example.grouppurchase.participation.domain.Participations;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcParticipations implements Participations {
    private static final String OWNED = "SELECT * FROM app.participations WHERE buyer_id = :buyer";
    private final JdbcClient jdbc;

    public JdbcParticipations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Participation participation) {
        jdbc.sql("""
                INSERT INTO app.participations (id, group_purchase_id, buyer_id, quantity, unit_price, status, applied_at)
                VALUES (:id, :purchase, :buyer, :quantity, :price, 'APPLIED', :applied)
                """).param("id", participation.id()).param("purchase", participation.groupPurchaseId())
                .param("buyer", participation.buyerId()).param("quantity", participation.quantity())
                .param("price", participation.unitPrice()).param("applied", participation.appliedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Participation> findOwned(UUID buyerId, UUID id) {
        return jdbc.sql(OWNED + " AND id = :id").param("buyer", buyerId).param("id", id)
                .query(JdbcParticipations::read).optional();
    }

    @Override
    public Optional<Participation> lockOwned(UUID buyerId, UUID groupPurchaseId, UUID id) {
        return jdbc.sql(OWNED + " AND group_purchase_id = :purchase AND id = :id FOR UPDATE")
                .param("buyer", buyerId).param("purchase", groupPurchaseId).param("id", id)
                .query(JdbcParticipations::read).optional();
    }

    @Override
    public long appliedQuantity(UUID groupPurchaseId, UUID buyerId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(quantity), 0) FROM app.participations
                WHERE group_purchase_id = :purchase AND buyer_id = :buyer AND status = 'APPLIED'
                """).param("purchase", groupPurchaseId).param("buyer", buyerId).query(Long.class).single();
    }

    @Override
    public void cancel(UUID id, Instant cancelledAt) {
        int updated = jdbc.sql("""
                UPDATE app.participations SET status = 'CANCELLED', cancelled_at = :cancelled
                WHERE id = :id AND status = 'APPLIED'
                """).param("cancelled", cancelledAt.atOffset(ZoneOffset.UTC)).param("id", id).update();
        if (updated != 1) throw new IllegalStateException("Locked participation was not applied");
    }

    @Override
    public List<Participation> listOwned(UUID buyerId, Instant afterTime, UUID afterId, int limit) {
        String cursor = afterTime == null ? "" : " AND (applied_at, id) < (:afterTime, :afterId)";
        var query = jdbc.sql(OWNED + cursor + " ORDER BY applied_at DESC, id DESC LIMIT :limit")
                .param("buyer", buyerId).param("limit", limit);
        if (afterTime != null) query.param("afterTime", afterTime.atOffset(ZoneOffset.UTC)).param("afterId", afterId);
        return query.query(JdbcParticipations::read).list();
    }

    private static Participation read(ResultSet row, int index) throws SQLException {
        var cancelled = row.getObject("cancelled_at", OffsetDateTime.class);
        return new Participation(row.getObject("id", UUID.class), row.getObject("group_purchase_id", UUID.class),
                row.getObject("buyer_id", UUID.class), Participation.Status.valueOf(row.getString("status")),
                row.getInt("quantity"), row.getLong("unit_price"), row.getObject("applied_at", OffsetDateTime.class).toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }
}
