package com.supportplatform.whatsapp;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.user.UserRole;
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

    private final WhatsAppConnectionRepository connectionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public WhatsAppConnectionService(WhatsAppConnectionRepository connectionRepository, ApplicationEventPublisher eventPublisher) {
        this.connectionRepository = connectionRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Upsert: a tenant reconnecting (e.g. token rotation) replaces its existing row rather than erroring. */
    @Transactional
    public WhatsAppConnection connect(UUID tenantId, UUID actorUserId, UserRole actingRole, String phoneNumberId, String wabaId, String accessToken) {
        requireOwnerOrAdmin(actingRole);

        WhatsAppConnection connection = connectionRepository.findByTenantId(tenantId)
                .map(existing -> {
                    existing.reconfigure(phoneNumberId, wabaId, accessToken);
                    return existing;
                })
                .orElseGet(() -> connectionRepository.save(new WhatsAppConnection(tenantId, phoneNumberId, wabaId, accessToken)));

        // Never the access token — audit-domain.md §2.
        eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, AuditAction.WHATSAPP_CONNECTED,
                "WHATSAPP_CONNECTION", connection.getId(), "Connected WhatsApp (phone_number_id=" + phoneNumberId + ")"));
        return connection;
    }

    @Transactional(readOnly = true)
    public WhatsAppConnection getForTenant(UUID tenantId, UserRole actingRole) {
        requireOwnerOrAdmin(actingRole);
        return connectionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "WhatsApp is not connected for this tenant"));
    }

    private void requireOwnerOrAdmin(UserRole actingRole) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can manage the WhatsApp connection");
        }
    }
}
