package com.supportplatform.notification;

import com.supportplatform.apikey.ApiKeyPrincipal;
import com.supportplatform.notification.dto.NotificationStatusResponse;
import com.supportplatform.notification.dto.NotificationUsageResponse;
import com.supportplatform.notification.dto.SendNotificationRequest;
import com.supportplatform.notification.dto.SendNotificationResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
    private final NotificationUsageService usageService;

    public NotificationSendController(NotificationSendService sendService, NotificationLogService logService,
                                        NotificationUsageService usageService) {
        this.sendService = sendService;
        this.logService = logService;
        this.usageService = usageService;
    }

    /**
     * {@code Idempotency-Key} is optional, for backward compatibility with
     * integrations that predate it. Supplying it is strictly better: without
     * one there is no deduplication at all, and none is inferred — the
     * template in use carries no parameters, so two requests for different
     * events are byte-identical and only the caller can say which is which.
     *
     * <p>A suppressed retry answers {@code 200 OK} with
     * {@code X-Idempotent-Replay: true} and the original send's body, rather
     * than the {@code 202 Accepted} a fresh send gets. The distinct status
     * matters: a caller retrying because it never saw the first response
     * needs to tell "we already did this" from "we just did this", and the
     * body alone cannot say.
     */
    @PostMapping("/send")
    public ResponseEntity<SendNotificationResponse> send(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SendNotificationRequest request) {
        SendOutcome outcome = sendService.send(principal, request, idempotencyKey);
        return ResponseEntity
                .status(outcome.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("X-Idempotent-Replay", Boolean.toString(outcome.replay()))
                .body(SendNotificationResponse.from(outcome.log()));
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
     * What this key has sent. Scoped to the key's own tenant (Rule 3) —
     * there is no parameter through which a caller could name another —
     * so an integrator can reconcile its own volume without anyone having
     * to export a report for it.
     *
     * <p>Dates are whole UTC days and both bounds are inclusive. Omitting
     * them gives the last {@code 30} days.
     */
    @GetMapping("/usage")
    public NotificationUsageResponse usage(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return usageService.forApiKey(principal.tenantId(), from, to);
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
