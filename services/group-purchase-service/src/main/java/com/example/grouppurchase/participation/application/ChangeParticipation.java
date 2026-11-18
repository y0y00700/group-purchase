package com.example.grouppurchase.participation.application;

import com.example.grouppurchase.participation.application.ParticipationChange.Operation;
import com.example.grouppurchase.participation.application.ParticipationChange.Receipt;
import com.example.grouppurchase.participation.application.ParticipationIdempotency.Key;
import com.example.grouppurchase.participation.application.ParticipationIdempotency.StoredResult;
import com.example.grouppurchase.participation.domain.Participation;
import com.example.grouppurchase.participation.domain.ParticipationConflict;
import com.example.grouppurchase.participation.domain.Participations;
import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.GroupPurchases;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.example.grouppurchase.application.RequestFailure.IDEMPOTENCY_KEY_CONFLICT;
import static com.example.grouppurchase.application.RequestFailure.NOT_FOUND;
import static com.example.grouppurchase.participation.domain.ParticipationConflict.Reason.*;

@Service
public class ChangeParticipation {
    private final GroupPurchases purchases;
    private final Participations participations;
    private final ParticipationIdempotency idempotency;

    public ChangeParticipation(GroupPurchases purchases, Participations participations,
            ParticipationIdempotency idempotency) {
        this.purchases = purchases;
        this.participations = participations;
        this.idempotency = idempotency;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(UUID buyerId, UUID purchaseId, int quantity, String keyValue) {
        purchases.limitLockWait();
        var recruitment = purchases.lockPublished(purchaseId).orElseThrow(NOT_FOUND::exception);
        var change = new ParticipationChange(Operation.APPLY, purchaseId, quantity);
        var key = new Key(buyerId, change.operation(), keyValue);
        var previous = claim(key, change);
        if (previous.isPresent()) return replay(buyerId, change, previous.orElseThrow());

        var purchase = recruitment.purchase();
        var now = purchases.currentTime();
        if (purchase.status() != GroupPurchase.Status.RECRUITING || !now.isBefore(purchase.terms().finalDeadline())) {
            throw new ParticipationConflict(RECRUITMENT_CLOSED);
        }
        int remaining = purchase.initialRecruitmentTarget() - recruitment.recruitedQuantity();
        long buyerQuantity = participations.appliedQuantity(purchaseId, buyerId);
        Participation.checkQuantity(quantity, remaining, purchase.terms().baseQuantity(), buyerQuantity);

        UUID id = UUID.randomUUID();
        participations.insert(new Participation(id, purchaseId, buyerId, Participation.Status.APPLIED,
                quantity, purchase.terms().unitPrice(), now, null));
        purchases.changeRecruitedQuantity(purchaseId, quantity);
        return complete(key, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(UUID buyerId, UUID id, String keyValue) {
        purchases.limitLockWait();
        UUID purchaseId = participations.findOwned(buyerId, id).orElseThrow(NOT_FOUND::exception).groupPurchaseId();
        purchases.lockPublished(purchaseId).orElseThrow(NOT_FOUND::exception);
        var participation = participations.lockOwned(buyerId, purchaseId, id).orElseThrow(NOT_FOUND::exception);
        var change = new ParticipationChange(Operation.CANCEL, id, null);
        var key = new Key(buyerId, change.operation(), keyValue);
        var previous = claim(key, change);
        if (previous.isPresent()) return replay(buyerId, change, previous.orElseThrow());

        if (participation.status() != Participation.Status.APPLIED) {
            throw new ParticipationConflict(PARTICIPATION_ALREADY_CANCELLED);
        }
        var now = purchases.currentTime();
        participations.cancel(id, now.isBefore(participation.appliedAt()) ? participation.appliedAt() : now);
        purchases.changeRecruitedQuantity(purchaseId, -participation.quantity());
        return complete(key, id);
    }

    private Optional<StoredResult> claim(Key key, ParticipationChange change) {
        int version = ParticipationChange.VERSION;
        return idempotency.claim(key, version, change.fingerprint(version));
    }

    private Receipt replay(UUID buyerId, ParticipationChange change, StoredResult stored) {
        if (!MessageDigest.isEqual(change.fingerprint(stored.fingerprintVersion()), stored.fingerprint())) {
            throw IDEMPOTENCY_KEY_CONFLICT.exception();
        }
        participations.findOwned(buyerId, stored.receipt().id()).orElseThrow(NOT_FOUND::exception);
        return stored.receipt();
    }

    private Receipt complete(Key key, UUID id) {
        var receipt = Receipt.forChange(key.operation(), id);
        idempotency.complete(key, receipt);
        return receipt;
    }
}
