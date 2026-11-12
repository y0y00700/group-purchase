package com.example.grouppurchase.web;

import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthProbeController {
    @GatewayIdentityInterceptor.RequiresGateway
    @RequestMapping(path = "/api/group-purchases/auth-probe",
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    GatewayIdentityInterceptor.Identity probe(
            @RequestAttribute(GatewayIdentityInterceptor.IDENTITY) GatewayIdentityInterceptor.Identity identity) {
        return identity;
    }
}
