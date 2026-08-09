package com.supportplatform.whatsapp;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.whatsapp.dto.ConnectWhatsAppRequest;
import com.supportplatform.whatsapp.dto.WhatsAppConnectionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/whatsapp/connection")
public class WhatsAppConnectionController {

    private final WhatsAppConnectionService connectionService;

    public WhatsAppConnectionController(WhatsAppConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping
    public WhatsAppConnectionResponse connect(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                               @Valid @RequestBody ConnectWhatsAppRequest request) {
        WhatsAppConnection connection = connectionService.connect(principal.getTenantId(), principal.getUserId(), principal.getRole(),
                request.phoneNumberId(), request.wabaId(), request.accessToken());
        return WhatsAppConnectionResponse.from(connection);
    }

    @GetMapping
    public WhatsAppConnectionResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return WhatsAppConnectionResponse.from(connectionService.getForTenant(principal.getTenantId(), principal.getRole()));
    }
}
