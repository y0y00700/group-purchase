package com.example.grouppurchase.purchase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.grouppurchase.purchase.domain.DraftConflict;
import com.example.grouppurchase.purchase.domain.GroupPurchase;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseChangeTests {
    @Test
    void fingerprintVersionOneHasStableGoldenValues() {
        var empty = PurchaseChange.register(new SellingTerms(null, null, null, null, null));
        var publish = new PurchaseChange(PurchaseChange.Operation.PUBLISH,
                UUID.fromString("126260b2-de13-43b6-8503-a76ad6974d5a"), 4L, null);
        assertThat(HexFormat.of().formatHex(empty.fingerprint(1)))
                .isEqualTo("9318536c7a8f9758f3329da9580904b7e529bae5172d5307049f3bb2fb782486");
        assertThat(HexFormat.of().formatHex(publish.fingerprint(1)))
                .isEqualTo("7dc6028bcab7fba08a32ebb76d0af44bfdc37dc4b895a5e2631b7df8d27b7a1c");
    }

    @Test
    void publicationDeadlineIsExclusiveAtMillisecondPrecision() {
        var deadline = Instant.parse("2090-01-01T00:00:00.001Z");
        var terms = new SellingTerms("cup", "description", 1_000L, 11, deadline);
        var draft = GroupPurchase.draft(UUID.randomUUID(), UUID.randomUUID(), terms, deadline.minusSeconds(1));
        assertThat(draft.publish(1, deadline.minusMillis(1)).initialRecruitmentTarget()).isEqualTo(14);
        for (var now : new Instant[]{deadline, deadline.plusMillis(1)}) {
            assertThatThrownBy(() -> draft.publish(1, now))
                    .isInstanceOfSatisfying(DraftConflict.class,
                            failure -> assertThat(failure.reason()).isEqualTo(DraftConflict.Reason.DEADLINE_PASSED));
        }
    }
}
