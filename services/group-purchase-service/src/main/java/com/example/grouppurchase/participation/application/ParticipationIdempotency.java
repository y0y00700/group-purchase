package com.example.grouppurchase.participation.application;

import com.example.grouppurchase.participation.application.ParticipationChange.Operation;
import com.example.grouppurchase.participation.application.ParticipationChange.Receipt;
import java.util.Optional;
import java.util.UUID;

public interface ParticipationIdempotency {
    Optional<StoredResult> claim(Key key, int version, byte[] fingerprint);

    void complete(Key key, Receipt receipt);

    record Key(UUID buyerId, Operation operation, String value) { }

    record StoredResult(int fingerprintVersion, byte[] fingerprint, Receipt receipt) { }
}
