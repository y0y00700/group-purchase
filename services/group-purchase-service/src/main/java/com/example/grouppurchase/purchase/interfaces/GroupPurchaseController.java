package com.example.grouppurchase.purchase.interfaces;

import com.example.grouppurchase.purchase.application.ChangeGroupPurchase;
import com.example.grouppurchase.purchase.application.FindGroupPurchases;
import com.example.grouppurchase.purchase.application.PurchaseChange.Receipt;
import com.example.grouppurchase.web.ApiInput;
import com.example.grouppurchase.web.GatewayIdentityInterceptor;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.Identity;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.RequiresRole;
import com.example.grouppurchase.web.GatewayIdentityInterceptor.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/group-purchases", produces = MediaType.APPLICATION_JSON_VALUE)
public class GroupPurchaseController {
    private final ChangeGroupPurchase commands;
    private final FindGroupPurchases queries;

    public GroupPurchaseController(ChangeGroupPurchase commands, FindGroupPurchases queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    PurchaseResponses.Listing open(@RequestParam MultiValueMap<String, String> parameters,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var page = ApiInput.page(parameters);
        return PurchaseResponses.listing(queries.open(page.limit(), page.cursor()), false);
    }

    @GetMapping("/{id}")
    Map<String, Object> published(@PathVariable String id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return PurchaseResponses.detail(queries.published(ApiInput.id(id)), false);
    }

    @RequiresRole(Role.SELLER)
    @GetMapping("/mine")
    PurchaseResponses.Listing mine(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            @RequestParam MultiValueMap<String, String> parameters) {
        var page = ApiInput.page(parameters);
        return PurchaseResponses.listing(queries.owned(identity.memberId(), page.limit(), page.cursor()), true);
    }

    @RequiresRole(Role.SELLER)
    @GetMapping("/mine/{id}")
    Map<String, Object> owned(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            @PathVariable String id) {
        return PurchaseResponses.detail(queries.owned(identity.memberId(), ApiInput.id(id)), true);
    }

    @RequiresRole(Role.SELLER)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> register(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            HttpServletRequest request, @RequestBody byte[] body) {
        return response(commands.register(identity.memberId(), key(request), PurchaseInput.register(body)));
    }

    @RequiresRole(Role.SELLER)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> revise(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            HttpServletRequest request, @PathVariable String id, @RequestBody byte[] body) {
        return response(commands.changeDraft(identity.memberId(), key(request), PurchaseInput.revise(id, body)));
    }

    @RequiresRole(Role.SELLER)
    @PostMapping(path = "/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> publish(@RequestAttribute(GatewayIdentityInterceptor.IDENTITY) Identity identity,
            HttpServletRequest request, @PathVariable String id, @RequestBody byte[] body) {
        return response(commands.changeDraft(identity.memberId(), key(request), PurchaseInput.publish(id, body)));
    }

    private static String key(HttpServletRequest request) {
        return ApiInput.key(Collections.list(request.getHeaders("Idempotency-Key")));
    }

    private static ResponseEntity<byte[]> response(Receipt receipt) {
        var response = ResponseEntity.status(receipt.status()).contentType(MediaType.APPLICATION_JSON);
        if (receipt.location() != null) response.location(URI.create(receipt.location()));
        return response.body(receipt.bodyBytes());
    }
}
