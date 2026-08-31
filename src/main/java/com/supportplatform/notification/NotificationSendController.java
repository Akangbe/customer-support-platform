package com.supportplatform.notification;

import com.supportplatform.apikey.ApiKeyPrincipal;
import com.supportplatform.notification.dto.NotificationStatusResponse;
import com.supportplatform.notification.dto.SendNotificationRequest;
import com.supportplatform.notification.dto.SendNotificationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The machine-to-machine send API. Sits behind
 * {@code ApiKeySecurityConfig}'s stateless chain, so by the time a request
 * reaches here the key has been verified, its tenant resolved, and its
 * rate limit charged.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationSendController {

    private final NotificationSendService sendService;
    private final NotificationLogService logService;

    public NotificationSendController(NotificationSendService sendService, NotificationLogService logService) {
        this.sendService = sendService;
        this.logService = logService;
    }

    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SendNotificationResponse send(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                           @Valid @RequestBody SendNotificationRequest request) {
        return SendNotificationResponse.from(sendService.send(principal, request));
    }

    /**
     * Did it land? Reflects the delivery webhooks Meta sends us
     * (whatsapp-domain.md §7): SENT the moment we relayed it, then
     * DELIVERED / READ, or FAILED if Meta reports it undeliverable.
     *
     * <p>Both lookups are scoped to the key's own tenant, so one tenant
     * cannot read another's send by guessing an id (Rule 3) — a miss is a
     * 404 either way, which also means an id from another tenant is
     * indistinguishable from one that doesn't exist.
     */
    @GetMapping("/{notificationId}")
    public NotificationStatusResponse getById(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                                @PathVariable UUID notificationId) {
        return NotificationStatusResponse.from(logService.getWithinTenant(principal.tenantId(), notificationId));
    }

    /**
     * The same lookup by Meta's own id, for a caller that kept
     * {@code metaMessageId} rather than our {@code notificationId}. A query
     * parameter rather than a path segment because a {@code wamid.} value is
     * base64-ish and can carry characters that need escaping in a path.
     */
    @GetMapping
    public NotificationStatusResponse getByMetaMessageId(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                                           @RequestParam String metaMessageId) {
        return NotificationStatusResponse.from(logService.getByMetaMessageId(principal.tenantId(), metaMessageId));
    }
}
