package com.example.grouppurchase.purchase.application;

import com.example.grouppurchase.application.PageCursor;
import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.GroupPurchases;
import com.example.grouppurchase.purchase.domain.GroupPurchases.Position;
import com.example.grouppurchase.purchase.domain.GroupPurchases.View;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static com.example.grouppurchase.application.RequestFailure.*;

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
        return page(purchases.listOpen(position(PageCursor.PUBLIC, cursor), limit + 1), limit, PageCursor.PUBLIC);
    }

    public Page owned(UUID sellerId, int limit, String cursor) {
        return page(purchases.listOwned(sellerId, position(PageCursor.MINE, cursor), limit + 1), limit, PageCursor.MINE);
    }

    private static Position position(PageCursor scope, String cursor) {
        var position = scope.decode(cursor);
        return position == null ? null : new Position(position.time(), position.id());
    }

    private static Page page(List<View> rows, int limit, PageCursor scope) {
        if (rows.size() <= limit) return new Page(rows, null);
        GroupPurchase last = rows.get(limit - 1).purchase();
        Instant time = scope == PageCursor.PUBLIC ? last.publishedAt() : last.createdAt();
        return new Page(rows.subList(0, limit), scope.encode(new PageCursor.Position(time, last.id())));
    }

    public record Page(List<View> items, String nextCursor) { }

}
