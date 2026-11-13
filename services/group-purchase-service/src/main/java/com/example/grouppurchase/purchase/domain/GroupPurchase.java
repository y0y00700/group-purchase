package com.example.grouppurchase.purchase.domain;

import java.time.Instant;
import java.util.UUID;

import static com.example.grouppurchase.purchase.domain.DraftConflict.Reason.*;

public record GroupPurchase(UUID id, UUID sellerId, Status status, SellingTerms terms,
        Integer initialRecruitmentTarget, long draftVersion, Instant createdAt, Instant updatedAt,
        Instant publishedAt) {

    public enum Status { DRAFT, RECRUITING }

    public static GroupPurchase draft(UUID id, UUID sellerId, SellingTerms terms, Instant now) {
        return new GroupPurchase(id, sellerId, Status.DRAFT, terms, null, 1, now, now, null);
    }

    public GroupPurchase revise(long expectedVersion, SellingTerms replacement, Instant now) {
        requireDraftVersion(expectedVersion);
        return new GroupPurchase(id, sellerId, Status.DRAFT, replacement, null, draftVersion + 1,
                createdAt, now, null);
    }

    public GroupPurchase publish(long expectedVersion, Instant now) {
        requireDraftVersion(expectedVersion);
        if (!terms.complete()) throw new DraftConflict(NOT_READY);
        if (!terms.finalDeadline().isAfter(now)) throw new DraftConflict(DEADLINE_PASSED);
        return new GroupPurchase(id, sellerId, Status.RECRUITING, terms, terms.initialTarget(),
                draftVersion, createdAt, now, now);
    }

    private void requireDraftVersion(long expectedVersion) {
        if (status != Status.DRAFT) throw new DraftConflict(NOT_DRAFT);
        if (draftVersion != expectedVersion) throw new DraftConflict(DRAFT_VERSION_MISMATCH);
    }

    public Integer projectedInitialRecruitmentTarget() {
        return status == Status.DRAFT ? terms.initialTarget() : null;
    }
}
