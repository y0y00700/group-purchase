package com.example.grouppurchase.purchase.application;

import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.GroupPurchases;
import com.example.grouppurchase.purchase.domain.GroupPurchases.Position;
import com.example.grouppurchase.purchase.domain.GroupPurchases.View;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static com.example.grouppurchase.purchase.application.PurchaseRequestFailure.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

@Service
public class FindGroupPurchases {
    private final GroupPurchases purchases;

    public FindGroupPurchases(GroupPurchases purchases) {
        this.purchases = purchases;
    }

    public View published(UUID id) {
        return purchases.findPublished(id).orElseThrow(NOT_FOUND::exception);
    }

    public View owned(UUID sellerId, UUID id) {
        return purchases.findOwned(id, sellerId).orElseThrow(NOT_FOUND::exception);
    }

    public Page open(int limit, String cursor) {
        return page(purchases.listOpen(Scope.PUBLIC.decode(cursor), limit + 1), limit, Scope.PUBLIC);
    }

    public Page owned(UUID sellerId, int limit, String cursor) {
        return page(purchases.listOwned(sellerId, Scope.MINE.decode(cursor), limit + 1), limit, Scope.MINE);
    }

    private static Page page(List<View> rows, int limit, Scope scope) {
        if (rows.size() <= limit) return new Page(rows, null);
        GroupPurchase last = rows.get(limit - 1).purchase();
        Instant time = scope == Scope.PUBLIC ? last.publishedAt() : last.createdAt();
        return new Page(rows.subList(0, limit), scope.encode(new Position(time, last.id())));
    }

    public record Page(List<View> items, String nextCursor) { }

    private enum Scope {
        PUBLIC, MINE;

        private static final long MIN_MILLIS = Instant.parse("0001-01-01T00:00:00Z").toEpochMilli();
        private static final long MAX_MILLIS = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

        String encode(Position position) {
            String value = name() + ":" + position.time().toEpochMilli() + ":" + position.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(US_ASCII));
        }

        Position decode(String cursor) {
            if (cursor == null) return null;
            if (cursor.length() > 96) throw INVALID_REQUEST.exception();
            try {
                String[] fields = new String(Base64.getUrlDecoder().decode(cursor), US_ASCII).split(":", -1);
                if (fields.length != 3 || !name().equals(fields[0])) throw INVALID_REQUEST.exception();
                long millis = Long.parseLong(fields[1]);
                if (millis < MIN_MILLIS || millis > MAX_MILLIS) throw INVALID_REQUEST.exception();
                var position = new Position(Instant.ofEpochMilli(millis), UUID.fromString(fields[2]));
                if (!encode(position).equals(cursor)) throw INVALID_REQUEST.exception();
                return position;
            } catch (IllegalArgumentException invalid) {
                throw INVALID_REQUEST.exception();
            }
        }
    }
}
