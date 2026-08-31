package com.supportplatform.whatsapp;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.whatsapp.dto.RegisterTemplateRequest;
import com.supportplatform.whatsapp.dto.WhatsAppTemplateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Manages the tenant's template allowlist from the dashboard. Session
 * authenticated on the existing filter chain — the notification API's keys
 * only read this list, they never change it.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/templates")
public class WhatsAppTemplateController {

    private final WhatsAppTemplateService templateService;

    public WhatsAppTemplateController(WhatsAppTemplateService templateService) {
        this.templateService = templateService;
    }

    /** Upsert by name — re-posting a known name updates its status. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WhatsAppTemplateResponse register(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                               @Valid @RequestBody RegisterTemplateRequest request) {
        return WhatsAppTemplateResponse.from(templateService.register(principal.getTenantId(), principal.getRole(),
                request.name(), request.status()));
    }

    /** Readable by any role: an agent needs to see which templates exist to use them. */
    @GetMapping
    public List<WhatsAppTemplateResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return templateService.listForTenant(principal.getTenantId()).stream()
                .map(WhatsAppTemplateResponse::from)
                .toList();
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID templateId) {
        templateService.delete(principal.getTenantId(), principal.getRole(), templateId);
    }
}
