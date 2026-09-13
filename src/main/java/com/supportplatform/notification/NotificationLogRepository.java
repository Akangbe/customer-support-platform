package com.supportplatform.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /** Tenant-scoped by construction (Rule 3) — there is deliberately no unscoped finder. */
    Page<NotificationLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Optional<NotificationLog> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs both the tenant status lookup and the delivery webhook (unique index in V12). */
    Optional<NotificationLog> findByTenantIdAndMetaMessageId(UUID tenantId, String metaMessageId);

    /**
     * The idempotency lookup (unique index in V15). At most one row can
     * hold a given key for a tenant, so this is the whole replay decision.
     */
    Optional<NotificationLog> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * How many notifications this recipient has already had for this
     * template since {@code windowStart} — the per-recipient velocity
     * ceiling (V14 index).
     *
     * <p>Counts rows of every status on purpose. Excluding FAILED would
     * read more naturally — an undelivered message did not reach anyone —
     * but it would also disable the ceiling exactly when it is most needed:
     * during the 2026-09-05 billing block every send failed, and a caller
     * looping on those failures is the pathological case this is here to
     * bound. A send that was attempted counts, whatever Meta made of it.
     */
    @Query("""
            SELECT count(n) FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.recipient = :recipient
               AND n.templateName = :templateName
               AND n.createdAt >= :windowStart
            """)
    long countInWindow(@Param("tenantId") UUID tenantId,
                        @Param("recipient") String recipient,
                        @Param("templateName") String templateName,
                        @Param("windowStart") Instant windowStart);

    /**
     * The earliest send still inside the window, which is what fixes the
     * {@code Retry-After} the caller is given: once this one ages out there
     * is room for another.
     */
    @Query("""
            SELECT min(n.createdAt) FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.recipient = :recipient
               AND n.templateName = :templateName
               AND n.createdAt >= :windowStart
            """)
    Optional<Instant> findOldestInWindow(@Param("tenantId") UUID tenantId,
                                           @Param("recipient") String recipient,
                                           @Param("templateName") String templateName,
                                           @Param("windowStart") Instant windowStart);
}
