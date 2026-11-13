package com.example.grouppurchase.purchase.infrastructure;

import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.GroupPurchases;
import com.example.grouppurchase.purchase.domain.SellingTerms;
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
public class JdbcGroupPurchases implements GroupPurchases {
    private static final String VIEW = """
            SELECT p.*, COALESCE(p.status = 'RECRUITING' AND p.final_deadline > statement_timestamp(), false)
                AS recruitment_open
            FROM app.group_purchases p
            """;

    private final JdbcClient jdbc;

    public JdbcGroupPurchases(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void limitLockWait() {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
    }

    @Override
    public Instant currentTime() {
        return jdbc.sql("SELECT date_trunc('milliseconds', clock_timestamp())")
                .query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public void insert(GroupPurchase purchase) {
        bind(jdbc.sql("""
                INSERT INTO app.group_purchases (
                    id, seller_id, status, product_name, product_description, unit_price, base_quantity,
                    final_deadline, initial_recruitment_target, draft_version, created_at, updated_at, published_at
                ) VALUES (
                    :id, :seller, :status, :name, :description, :price, :quantity, :deadline,
                    :target, :version, :created, :updated, :published
                )
                """), purchase).param("seller", purchase.sellerId())
                .param("created", offset(purchase.createdAt())).update();
    }

    @Override
    public void update(GroupPurchase purchase) {
        int updated = bind(jdbc.sql("""
                UPDATE app.group_purchases
                SET status = :status, product_name = :name, product_description = :description,
                    unit_price = :price, base_quantity = :quantity, final_deadline = :deadline,
                    initial_recruitment_target = :target, draft_version = :version, updated_at = :updated,
                    published_at = :published
                WHERE id = :id
                """), purchase).update();
        if (updated != 1) throw new IllegalStateException("Locked purchase disappeared");
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, GroupPurchase purchase) {
        var terms = purchase.terms();
        return sql.param("id", purchase.id()).param("status", purchase.status().name())
                .param("name", terms.productName()).param("description", terms.productDescription())
                .param("price", terms.unitPrice()).param("quantity", terms.baseQuantity())
                .param("deadline", offset(terms.finalDeadline())).param("target", purchase.initialRecruitmentTarget())
                .param("version", purchase.draftVersion()).param("updated", offset(purchase.updatedAt()))
                .param("published", offset(purchase.publishedAt()));
    }

    @Override
    public Optional<GroupPurchase> lockOwned(UUID id, UUID sellerId) {
        return jdbc.sql("SELECT * FROM app.group_purchases WHERE id = :id AND seller_id = :seller FOR UPDATE")
                .param("id", id).param("seller", sellerId).query(JdbcGroupPurchases::readPurchase).optional();
    }

    @Override
    public Optional<View> findOwned(UUID id, UUID sellerId) {
        return jdbc.sql(VIEW + "WHERE p.id = :id AND p.seller_id = :seller")
                .param("id", id).param("seller", sellerId).query(JdbcGroupPurchases::readView).optional();
    }

    @Override
    public Optional<View> findPublished(UUID id) {
        return jdbc.sql(VIEW + "WHERE p.id=:id AND p.published_at IS NOT NULL")
                .param("id", id).query(JdbcGroupPurchases::readView).optional();
    }

    @Override
    public List<View> listOwned(UUID sellerId, Position after, int limit) {
        var sql = list(VIEW + "WHERE p.seller_id = :seller", "created_at", after, limit);
        return sql.param("seller", sellerId).query(JdbcGroupPurchases::readView).list();
    }

    @Override
    public List<View> listOpen(Position after, int limit) {
        return list(VIEW + "WHERE p.status = 'RECRUITING' AND p.final_deadline > statement_timestamp()",
                "published_at", after, limit).query(JdbcGroupPurchases::readView).list();
    }

    private JdbcClient.StatementSpec list(String sql, String sortColumn, Position after, int limit) {
        // Both callers supply a fixed column. The cursor contributes only bound values.
        String cursor = after == null ? "" : " AND (p." + sortColumn + ", p.id) < (:afterTime, :afterId)";
        var query = jdbc.sql(sql + cursor + " ORDER BY p." + sortColumn + " DESC, p.id DESC LIMIT :limit")
                .param("limit", limit);
        if (after != null) query.param("afterTime", offset(after.time())).param("afterId", after.id());
        return query;
    }

    private static View readView(ResultSet row, int index) throws SQLException {
        return new View(readPurchase(row, index), row.getBoolean("recruitment_open"));
    }

    private static GroupPurchase readPurchase(ResultSet row, int index) throws SQLException {
        var terms = new SellingTerms(row.getString("product_name"), row.getString("product_description"),
                row.getObject("unit_price", Long.class), row.getObject("base_quantity", Integer.class),
                instant(row, "final_deadline"));
        return new GroupPurchase(row.getObject("id", UUID.class), row.getObject("seller_id", UUID.class),
                GroupPurchase.Status.valueOf(row.getString("status")), terms,
                row.getObject("initial_recruitment_target", Integer.class), row.getLong("draft_version"),
                instant(row, "created_at"), instant(row, "updated_at"), instant(row, "published_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
