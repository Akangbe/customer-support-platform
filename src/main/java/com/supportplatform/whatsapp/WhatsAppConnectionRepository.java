package com.supportplatform.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WhatsAppConnectionRepository extends JpaRepository<WhatsAppConnection, UUID> {

    Optional<WhatsAppConnection> findByTenantId(UUID tenantId);

    /** The join key Meta gives us to resolve tenant on an inbound webhook — deliberately not tenant-scoped (whatsapp-domain.md §5). */
    Optional<WhatsAppConnection> findByPhoneNumberId(String phoneNumberId);
}
