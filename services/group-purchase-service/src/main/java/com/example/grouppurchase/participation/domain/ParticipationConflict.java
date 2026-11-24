package com.example.grouppurchase.participation.domain;

public final class ParticipationConflict extends RuntimeException {
    public enum Reason {
        RECRUITMENT_CLOSED, RECRUITMENT_QUANTITY_EXCEEDED, BUYER_PARTICIPATION_LIMIT_EXCEEDED,
        PARTICIPATION_ALREADY_CANCELLED
    }

    private final Reason reason;

    public ParticipationConflict(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
