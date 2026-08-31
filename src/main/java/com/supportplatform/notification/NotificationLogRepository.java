package com.supportplatform.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /** Tenant-scoped by construction (Rule 3) — there is deliberately no unscoped finder. */
    Page<NotificationLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Optional<NotificationLog> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs both the tenant status lookup and the delivery webhook (unique index in V12). */
    Optional<NotificationLog> findByTenantIdAndMetaMessageId(UUID tenantId, String metaMessageId);
}
