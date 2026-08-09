package com.supportplatform.whatsapp;

import com.supportplatform.user.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant WhatsApp connection management (whatsapp-domain.md §2).
 * Owner/Admin only, per Product Vision's role table — workspace and
 * WhatsApp configuration is never a Manager/Agent duty.
 */
@Service
public class WhatsAppConnectionService {

    private final WhatsAppConnectionRepository connectionRepository;

    public WhatsAppConnectionService(WhatsAppConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    /** Upsert: a tenant reconnecting (e.g. token rotation) replaces its existing row rather than erroring. */
    @Transactional
    public WhatsAppConnection connect(UUID tenantId, UserRole actingRole, String phoneNumberId, String wabaId, String accessToken) {
        requireOwnerOrAdmin(actingRole);

        return connectionRepository.findByTenantId(tenantId)
                .map(existing -> {
                    existing.reconfigure(phoneNumberId, wabaId, accessToken);
                    return existing;
                })
                .orElseGet(() -> connectionRepository.save(new WhatsAppConnection(tenantId, phoneNumberId, wabaId, accessToken)));
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
