package com.supportplatform.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, UUID> {

    /** The send-path check. Tenant-scoped by construction (Rule 3) — there is deliberately no by-name-only finder. */
    Optional<WhatsAppTemplate> findByTenantIdAndName(UUID tenantId, String name);

    List<WhatsAppTemplate> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<WhatsAppTemplate> findByIdAndTenantId(UUID id, UUID tenantId);
}
