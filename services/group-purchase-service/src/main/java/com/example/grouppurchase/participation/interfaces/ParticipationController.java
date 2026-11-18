package com.example.grouppurchase.participation.interfaces;

import com.example.grouppurchase.participation.application.ChangeParticipation;
import com.example.grouppurchase.participation.application.FindParticipations;
import com.example.grouppurchase.participation.application.ParticipationChange.Receipt;
import com.example.grouppurchase.participation.domain.Participation;
import com.example.grouppurchase.web.ApiInput;
import com.example.grouppurchase.web.GatewayIdentityInterceptor;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.Identity;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.RequiresRole;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import static com.example.grouppurchase.application.RequestFailure.INVALID_REQUEST;

@RestController
@RequiresRole(Role.BUYER)
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ParticipationController {
    private final ChangeParticipation commands;
    private final FindParticipations queries;

    public ParticipationController(ChangeParticipation commands, FindParticipations queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping(path = "/group-purchases/{id}/participations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> apply(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            @PathVariable String id, HttpServletRequest request, @RequestBody byte[] body) {
        var object = ApiInput.object(body);
        var quantity = object.get("quantity");
        if (object.size() != 1 || quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()
                || quantity.intValue() < 1 || quantity.intValue() > 1_000_000) {
            throw INVALID_REQUEST.exception();
        }
        return response(commands.apply(identity.memberId(), ApiInput.id(id), quantity.intValue(), key(request)));
    }

    @PostMapping("/participations/{id}/cancel")
    ResponseEntity<byte[]> cancel(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            @PathVariable String id, HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) throw INVALID_REQUEST.exception();
        return response(commands.cancel(identity.memberId(), ApiInput.id(id), key(request)));
    }

    @GetMapping("/participations/mine")
    Listing mine(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            @RequestParam MultiValueMap<String, String> parameters) {
        var request = ApiInput.page(parameters);
        var page = queries.owned(identity.memberId(), request.limit(), request.cursor());
        return new Listing(page.items().stream().map(Detail::from).toList(), page.nextCursor());
    }

    @GetMapping("/participations/mine/{id}")
    Detail detail(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity, @PathVariable String id) {
        return Detail.from(queries.owned(identity.memberId(), ApiInput.id(id)));
    }

    private static String key(HttpServletRequest request) {
        return ApiInput.key(Collections.list(request.getHeaders("Idempotency-Key")));
    }

    private static ResponseEntity<byte[]> response(Receipt receipt) {
        var response = ResponseEntity.status(receipt.status()).contentType(MediaType.APPLICATION_JSON);
        if (receipt.location() != null) response.location(URI.create(receipt.location()));
        return response.body(receipt.bodyBytes());
    }

    record Listing(List<Detail> items, String nextCursor) { }

    record Detail(UUID id, UUID groupPurchaseId, Participation.Status status, int quantity, long unitPrice,
            long amount, Instant appliedAt, Instant cancelledAt) {
        static Detail from(Participation participation) {
            return new Detail(participation.id(), participation.groupPurchaseId(), participation.status(),
                    participation.quantity(), participation.unitPrice(), participation.amount(), participation.appliedAt(),
                    participation.cancelledAt());
        }
    }
}
