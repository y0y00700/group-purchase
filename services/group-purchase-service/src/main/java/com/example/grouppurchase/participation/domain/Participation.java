package com.example.grouppurchase.participation.domain;

import java.time.Instant;
import java.util.UUID;

public record Participation(UUID id, UUID groupPurchaseId, UUID buyerId, Status status, int quantity,
        long unitPrice, Instant appliedAt, Instant cancelledAt) {
    public enum Status { APPLIED, CANCELLED }

    public static void checkQuantity(int requested, int remaining, int baseQuantity, long buyerQuantity) {
        if (requested < 1 || requested > 1_000_000) throw new IllegalArgumentException("Invalid requested quantity");
        if (requested > remaining) {
            throw new ParticipationConflict(ParticipationConflict.Reason.RECRUITMENT_QUANTITY_EXCEEDED);
        }
        if (requested > baseQuantity / 10 - buyerQuantity) {
            throw new ParticipationConflict(ParticipationConflict.Reason.BUYER_PARTICIPATION_LIMIT_EXCEEDED);
        }
    }

    public long amount() {
        return quantity * unitPrice;
    }
}
