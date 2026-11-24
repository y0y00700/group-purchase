package com.example.grouppurchase.participation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Participations {
    void insert(Participation participation);

    Optional<Participation> findOwned(UUID buyerId, UUID id);

    Optional<Participation> lockOwned(UUID buyerId, UUID groupPurchaseId, UUID id);

    long appliedQuantity(UUID groupPurchaseId, UUID buyerId);

    void cancel(UUID id, Instant cancelledAt);

    List<Participation> listOwned(UUID buyerId, Instant afterTime, UUID afterId, int limit);
}
