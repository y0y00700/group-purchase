package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.grouppurchase.web.RequestIdFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fast LEARN-01 checks; HTTP/dispatch/MDC contracts live in ApiObservabilityTests. */
class RequestIdSelectionTests {
    @Test void reusesExactlyOneValidLowercaseId() {
        String id = "0123456789abcdef0123456789abcdef";
        assertThat(RequestIdFilter.selectRequestId(List.of(id))).isEqualTo(id);
    }
    @Test void replacesMissingMalformedAndAmbiguousHeaders() {
        String valid = "a".repeat(32);
        for (var headers : List.of(List.<String>of(), List.of(""), List.of("A".repeat(32)),
                List.of("g".repeat(32)), List.of("a".repeat(31)), List.of("a".repeat(33)),
                List.of(" " + valid), List.of(valid + "\r\nforged"),
                List.of(valid, valid), List.of(valid + "," + valid))) {
            assertThat(RequestIdFilter.selectRequestId(headers)).matches("[0-9a-f]{32}").isNotEqualTo(valid);
        }
    }
    @Test void generatesIndependentIdsForUnidentifiedRequests() {
        var ids = java.util.stream.IntStream.range(0, 100)
                .mapToObj(ignored -> RequestIdFilter.selectRequestId(List.of())).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }
}
