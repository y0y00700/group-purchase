package com.example.grouppurchase.application;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static com.example.grouppurchase.application.RequestFailure.INVALID_REQUEST;
import static java.nio.charset.StandardCharsets.US_ASCII;

public enum PageCursor {
    PUBLIC, MINE, PARTICIPATIONS;

    private static final long MIN_MILLIS = Instant.parse("0001-01-01T00:00:00Z").toEpochMilli();
    private static final long MAX_MILLIS = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

    public String encode(Position position) {
        String value = name() + ":" + position.time().toEpochMilli() + ":" + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(US_ASCII));
    }

    public Position decode(String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 96) throw INVALID_REQUEST.exception();
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), US_ASCII).split(":", -1);
            if (fields.length != 3 || !name().equals(fields[0])) throw INVALID_REQUEST.exception();
            long millis = Long.parseLong(fields[1]);
            if (millis < MIN_MILLIS || millis > MAX_MILLIS) throw INVALID_REQUEST.exception();
            var position = new Position(Instant.ofEpochMilli(millis), UUID.fromString(fields[2]));
            if (!encode(position).equals(cursor)) throw INVALID_REQUEST.exception();
            return position;
        } catch (IllegalArgumentException invalid) {
            throw INVALID_REQUEST.exception();
        }
    }
    public record Position(Instant time, UUID id) { }
}
