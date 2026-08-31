package com.supportplatform.notification;

import com.supportplatform.apikey.ApiKeyPrincipal;
import com.supportplatform.notification.dto.SendNotificationRequest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppConnection;
import com.supportplatform.whatsapp.WhatsAppConnectionRepository;
import com.supportplatform.whatsapp.WhatsAppGateway;
import com.supportplatform.whatsapp.WhatsAppTemplate;
import com.supportplatform.whatsapp.WhatsAppTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * Relays an approved template message to a tenant's own WhatsApp number on
 * behalf of that tenant's backend.
 *
 * <p>Every tenant-scoped read here uses {@code principal.tenantId()},
 * which came off the API key row (Rule 3) — the request body has no say in
 * which tenant is acting, and so no say in which WhatsApp number or token
 * gets used. The token itself is fetched, decrypted and handed to the
 * gateway inside this process; it is never returned, echoed or logged.
 *
 * <p>Deliberately not {@code @Transactional}: the log row must survive the
 * failure path. Each {@code save} runs in its own transaction, so writing
 * "FAILED" and then throwing does not roll that record back.
 */
@Service
public class NotificationSendService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendService.class);

    private final WhatsAppConnectionRepository connectionRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final WhatsAppGateway gateway;
    private final WhatsAppTemplateService templateService;

    public NotificationSendService(WhatsAppConnectionRepository connectionRepository,
                                     NotificationLogRepository notificationLogRepository,
                                     WhatsAppGateway gateway, WhatsAppTemplateService templateService) {
        this.connectionRepository = connectionRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.gateway = gateway;
        this.templateService = templateService;
    }

    public NotificationLog send(ApiKeyPrincipal principal, SendNotificationRequest request) {
        WhatsAppConnection connection = connectionRepository.findByTenantId(principal.tenantId())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        "WhatsApp is not connected for this tenant. Connect a number before sending notifications."));

        // Checked before the Meta call and before any log row: a template the
        // tenant doesn't own is a rejected request, not a failed send, so it
        // must not land in notification_log as a delivery failure.
        requireSendableTemplate(principal.tenantId(), request.templateName());

        SendResult result = gateway.sendTemplate(connection, request.recipient(), request.templateName(),
                request.languageCode(), request.bodyParams(), request.buttonUrlParam());

        if (result.success()) {
            NotificationLog sent = notificationLogRepository.save(NotificationLog.sent(principal.tenantId(),
                    principal.apiKeyId(), request.recipient(), request.templateName(), request.languageCode(),
                    result.waMessageId()));
            log.info("Notification {} sent for tenant {} via key {} (template '{}')",
                    sent.getId(), principal.tenantId(), principal.keyId(), request.templateName());
            return sent;
        }

        NotificationLog failed = notificationLogRepository.save(NotificationLog.failed(principal.tenantId(),
                principal.apiKeyId(), request.recipient(), request.templateName(), request.languageCode(),
                result.errorDetail()));
        // Meta's detail stays here, on our side of the boundary.
        log.warn("Notification {} failed for tenant {} via key {} (template '{}'): {}",
                failed.getId(), principal.tenantId(), principal.keyId(), request.templateName(), result.errorDetail());
        throw new NotificationDeliveryException(failed.getId());
    }

    /**
     * The allowlist gate (whatsapp-domain.md §8). Meta remains the real
     * authority — it can pause a template without telling us — so this
     * cannot guarantee a send succeeds. What it does guarantee is that a
     * tenant cannot send a template belonging to someone else's WABA, and
     * that the everyday mistakes (typo, not approved yet) come back as a
     * clear 4xx instead of a relayed Graph API error code.
     */
    private void requireSendableTemplate(UUID tenantId, String templateName) {
        WhatsAppTemplate template = templateService.findForTenant(tenantId, templateName)
                .orElseThrow(() -> TemplateNotAllowedException.unknown(templateName));

        if (!template.getStatus().isSendable()) {
            throw TemplateNotAllowedException.notApproved(templateName, template.getStatus());
        }
    }
}
