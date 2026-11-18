package com.example.grouppurchase.participation.application;

import com.example.grouppurchase.application.PageCursor;
import com.example.grouppurchase.participation.domain.Participation;
import com.example.grouppurchase.participation.domain.Participations;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static com.example.grouppurchase.application.RequestFailure.NOT_FOUND;

@Service
public class FindParticipations {
    private final Participations participations;

    public FindParticipations(Participations participations) {
        this.participations = participations;
    }

    public Participation owned(UUID buyerId, UUID id) {
        return participations.findOwned(buyerId, id).orElseThrow(NOT_FOUND::exception);
    }

    public Page owned(UUID buyerId, int limit, String cursor) {
        var after = PageCursor.PARTICIPATIONS.decode(cursor);
        var rows = participations.listOwned(buyerId, after == null ? null : after.time(),
                after == null ? null : after.id(), limit + 1);
        if (rows.size() <= limit) return new Page(rows, null);
        var last = rows.get(limit - 1);
        var nextCursor = PageCursor.PARTICIPATIONS.encode(new PageCursor.Position(last.appliedAt(), last.id()));
        return new Page(rows.subList(0, limit), nextCursor);
    }

    public record Page(List<Participation> items, String nextCursor) { }
}
