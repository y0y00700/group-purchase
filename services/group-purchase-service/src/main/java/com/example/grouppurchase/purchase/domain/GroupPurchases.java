package com.example.grouppurchase.purchase.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupPurchases {
    void limitLockWait();

    Instant currentTime();

    void insert(GroupPurchase purchase);

    void update(GroupPurchase purchase);

    Optional<GroupPurchase> lockOwned(UUID id, UUID sellerId);

    Optional<Recruitment> lockPublished(UUID id);

    void changeRecruitedQuantity(UUID id, int difference);

    Optional<View> findOwned(UUID id, UUID sellerId);

    Optional<View> findPublished(UUID id);

    List<View> listOwned(UUID sellerId, Position after, int limit);

    List<View> listOpen(Position after, int limit);

    record Recruitment(GroupPurchase purchase, int recruitedQuantity) { }

    record View(GroupPurchase purchase, boolean recruitmentOpen) { }

    record Position(Instant time, UUID id) { }
}
