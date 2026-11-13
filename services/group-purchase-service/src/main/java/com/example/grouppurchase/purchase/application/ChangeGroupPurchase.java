package com.example.grouppurchase.purchase.application;

import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.GroupPurchases;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.example.grouppurchase.purchase.application.PurchaseRequestFailure.*;

@Service
public class ChangeGroupPurchase {
    private final GroupPurchases purchases;
    private final PurchaseIdempotency idempotency;

    public ChangeGroupPurchase(GroupPurchases purchases, PurchaseIdempotency idempotency) {
        this.purchases = purchases;
        this.idempotency = idempotency;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PurchaseChange.Receipt register(UUID sellerId, String keyValue, SellingTerms terms) {
        purchases.limitLockWait();
        var change = PurchaseChange.register(terms);
        var key = new PurchaseIdempotency.Key(sellerId, change.operation(), keyValue);
        var previous = idempotency.claim(key, PurchaseChange.FINGERPRINT_VERSION,
                change.fingerprint(PurchaseChange.FINGERPRINT_VERSION));
        if (previous.isPresent()) {
            var stored = previous.orElseThrow();
            purchases.findOwned(stored.receipt().id(), sellerId).orElseThrow(NOT_FOUND::exception);
            return replay(change, stored);
        }

        UUID id = UUID.randomUUID();
        purchases.insert(GroupPurchase.draft(id, sellerId, terms, purchases.currentTime()));
        return complete(key, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PurchaseChange.Receipt changeDraft(UUID sellerId, String keyValue, PurchaseChange change) {
        purchases.limitLockWait();
        var current = purchases.lockOwned(change.targetId(), sellerId).orElseThrow(NOT_FOUND::exception);
        var key = new PurchaseIdempotency.Key(sellerId, change.operation(), keyValue);
        var previous = idempotency.claim(key, PurchaseChange.FINGERPRINT_VERSION,
                change.fingerprint(PurchaseChange.FINGERPRINT_VERSION));
        if (previous.isPresent()) return replay(change, previous.orElseThrow());

        var now = purchases.currentTime();
        var changed = switch (change.operation()) {
            case REVISE -> current.revise(change.draftVersion(), change.terms(), now);
            case PUBLISH -> current.publish(change.draftVersion(), now);
            case REGISTER -> throw new IllegalArgumentException("Draft change operation required");
        };

        purchases.update(changed);
        return complete(key, changed.id());
    }

    private PurchaseChange.Receipt replay(PurchaseChange change, PurchaseIdempotency.StoredResult stored) {
        if (!MessageDigest.isEqual(change.fingerprint(stored.fingerprintVersion()), stored.fingerprint())) {
            throw IDEMPOTENCY_KEY_CONFLICT.exception();
        }
        return stored.receipt();
    }

    private PurchaseChange.Receipt complete(PurchaseIdempotency.Key key, UUID id) {
        var receipt = PurchaseChange.Receipt.forChange(key.operation(), id);
        idempotency.complete(key, receipt);
        return receipt;
    }
}
