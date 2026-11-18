package com.example.grouppurchase.purchase.application;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseIdempotency {
    Optional<StoredResult> claim(Key key, int version, byte[] fingerprint);

    void complete(Key key, PurchaseChange.Receipt receipt);

    record Key(UUID memberId, PurchaseChange.Operation operation, String value) { }

    record StoredResult(int fingerprintVersion, byte[] fingerprint, PurchaseChange.Receipt receipt) { }
}
