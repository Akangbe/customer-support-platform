package com.supportplatform.whatsapp;

import com.supportplatform.user.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The per-tenant template allowlist. Owner/Admin only for changes, on the
 * same reasoning as {@link WhatsAppConnectionService}: which templates a
 * tenant may send is workspace configuration, not an agent's decision.
 *
 * <p>Registration is a manual step today, mirroring what the tenant already
 * had approved in Business Manager. Syncing it from Meta's
 * {@code /{waba-id}/message_templates} endpoint is the obvious next move,
 * but nothing yet requires it and Rule 5 says not to build it on spec —
 * the shape here (upsert by name, status enum matching Meta's own) is
 * deliberately the shape a sync job would write into.
 */
@Service
public class WhatsAppTemplateService {

    private final WhatsAppTemplateRepository templateRepository;

    public WhatsAppTemplateService(WhatsAppTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** Upsert: re-registering a name a tenant already has updates its status rather than erroring. */
    @Transactional
    public WhatsAppTemplate register(UUID tenantId, UserRole actingRole, String name, WhatsAppTemplateStatus status) {
        requireOwnerOrAdmin(actingRole);

        return templateRepository.findByTenantIdAndName(tenantId, name)
                .map(existing -> {
                    existing.updateStatus(status);
                    return existing;
                })
                .orElseGet(() -> templateRepository.save(new WhatsAppTemplate(tenantId, name, status)));
    }

    @Transactional(readOnly = true)
    public List<WhatsAppTemplate> listForTenant(UUID tenantId) {
        return templateRepository.findByTenantIdOrderByNameAsc(tenantId);
    }

    @Transactional
    public void delete(UUID tenantId, UserRole actingRole, UUID templateId) {
        requireOwnerOrAdmin(actingRole);

        WhatsAppTemplate template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Template not found"));
        templateRepository.delete(template);
    }

    /**
     * The send-path lookup, for {@code NotificationSendService}. Read-only
     * and tenant-scoped; no role check, because the caller here is an API
     * key acting for the tenant rather than a user with a role.
     */
    @Transactional(readOnly = true)
    public Optional<WhatsAppTemplate> findForTenant(UUID tenantId, String name) {
        return templateRepository.findByTenantIdAndName(tenantId, name);
    }

    private void requireOwnerOrAdmin(UserRole actingRole) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can manage WhatsApp templates");
        }
    }
}
