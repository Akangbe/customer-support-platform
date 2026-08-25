package com.supportplatform.whatsapp;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant WhatsApp connection management (whatsapp-domain.md §2).
 * Owner/Admin only, per Product Vision's role table — workspace and
 * WhatsApp configuration is never a Manager/Agent duty. Connecting is a
 * "configuration change" per FR-AUD-003 — the gap whatsapp-domain.md §1
 * flagged, closed here (audit-domain.md §1).
 */
@Service
public class WhatsAppConnectionService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConnectionService.class);

    private final WhatsAppConnectionRepository connectionRepository;
    private final WhatsAppGateway gateway;
    private final ApplicationEventPublisher eventPublisher;

    public WhatsAppConnectionService(WhatsAppConnectionRepository connectionRepository, WhatsAppGateway gateway,
                                      ApplicationEventPublisher eventPublisher) {
        this.connectionRepository = connectionRepository;
        this.gateway = gateway;
        this.eventPublisher = eventPublisher;
    }

    /** Upsert: a tenant reconnecting (e.g. token rotation) replaces its existing row rather than erroring. */
    @Transactional
    public WhatsAppConnection connect(UUID tenantId, UUID actorUserId, UserRole actingRole, String phoneNumberId, String wabaId, String accessToken) {
        requireOwnerOrAdmin(actingRole);
        return upsert(tenantId, actorUserId, phoneNumberId, wabaId, accessToken,
                "Connected WhatsApp (phone_number_id=" + phoneNumberId + ")");
    }

    /**
     * ADR-011 Phase C: the tenant authorized their own WABA to our app via
     * Meta's Embedded Signup flow (run entirely by the frontend). The code
     * exchange is load-bearing — without a real token there is nothing to
     * connect. Subscribing to the WABA's webhooks is best-effort: a failure
     * there still leaves the tenant with a usable connection, just without
     * inbound events until it's retried (whatsapp-domain.md §6).
     */
    @Transactional
    public WhatsAppConnection connectViaEmbeddedSignup(UUID tenantId, UUID actorUserId, UserRole actingRole,
                                                         String code, String phoneNumberId, String wabaId) {
        requireOwnerOrAdmin(actingRole);

        OAuthExchangeResult exchange = gateway.exchangeCodeForToken(code);
        if (!exchange.success()) {
            throw new WhatsAppCodeExchangeException(exchange.errorDetail());
        }

        if (!gateway.subscribeToWaba(wabaId, exchange.accessToken())) {
            log.warn("Connected tenant {} to WABA {} but webhook subscription failed; events will not arrive until this is retried",
                    tenantId, wabaId);
        }

        return upsert(tenantId, actorUserId, phoneNumberId, wabaId, exchange.accessToken(),
                "Connected WhatsApp via Embedded Signup (phone_number_id=" + phoneNumberId + ")");
    }

    @Transactional(readOnly = true)
    public WhatsAppConnection getForTenant(UUID tenantId, UserRole actingRole) {
        requireOwnerOrAdmin(actingRole);
        return connectionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "WhatsApp is not connected for this tenant"));
    }

    private WhatsAppConnection upsert(UUID tenantId, UUID actorUserId, String phoneNumberId, String wabaId,
                                       String accessToken, String auditDetail) {
        WhatsAppConnection connection = connectionRepository.findByTenantId(tenantId)
                .map(existing -> {
                    existing.reconfigure(phoneNumberId, wabaId, accessToken);
                    return existing;
                })
                .orElseGet(() -> connectionRepository.save(new WhatsAppConnection(tenantId, phoneNumberId, wabaId, accessToken)));

        // Never the access token — audit-domain.md §2.
        eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, AuditAction.WHATSAPP_CONNECTED,
                "WHATSAPP_CONNECTION", connection.getId(), auditDetail));
        return connection;
    }

    private void requireOwnerOrAdmin(UserRole actingRole) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can manage the WhatsApp connection");
        }
    }
}
