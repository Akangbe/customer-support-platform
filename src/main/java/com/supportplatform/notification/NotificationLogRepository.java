package com.supportplatform.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /** Tenant-scoped by construction (Rule 3) — there is deliberately no unscoped finder. */
    Page<NotificationLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Optional<NotificationLog> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs both the tenant status lookup and the delivery webhook (unique index in V12). */
    Optional<NotificationLog> findByTenantIdAndMetaMessageId(UUID tenantId, String metaMessageId);

    /**
     * Per-day totals for the usage view. Native because the bucketing is a
     * Postgres date cast; JPQL has no portable equivalent and inventing one
     * would be more code than the query.
     *
     * <p>Bucketed in UTC rather than the server's zone so the same range
     * returns the same numbers wherever it is read from, and so a day
     * boundary means one thing to everyone looking at it.
     */
    @Query(value = """
            SELECT (n.created_at AT TIME ZONE 'UTC')::date AS day,
                   count(*)                    AS sends,
                   count(DISTINCT n.recipient) AS recipients
              FROM notification_log n
             WHERE n.tenant_id = :tenantId
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<DailyUsageRow> findDailyUsage(@Param("tenantId") UUID tenantId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /** The status split for the same range — what was delivered, what failed, what is still in flight. */
    @Query("""
            SELECT n.status AS status, count(n) AS total
              FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.createdAt >= :from
               AND n.createdAt <  :to
             GROUP BY n.status
            """)
    List<StatusCountRow> countByStatusInRange(@Param("tenantId") UUID tenantId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /**
     * Distinct recipients across the whole range. Deliberately not the sum
     * of the daily figures: one customer messaged on three days is three
     * daily rows but one person, and the difference between "how many
     * messages" and "how many people" is the entire point of showing both.
     */
    @Query("""
            SELECT count(DISTINCT n.recipient)
              FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.createdAt >= :from
               AND n.createdAt <  :to
            """)
    long countDistinctRecipientsInRange(@Param("tenantId") UUID tenantId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);

    /**
     * Everything this tenant has sent since {@code from} — the running day
     * total behind the volume thresholds. Counts every status, because a
     * runaway caller hammering a template Meta keeps refusing is exactly the
     * case worth being told about.
     */
    @Query("""
            SELECT count(n) FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.createdAt >= :from
            """)
    long countSinceForTenant(@Param("tenantId") UUID tenantId, @Param("from") Instant from);

    /** One day's totals, as returned by {@link #findDailyUsage}. */
    interface DailyUsageRow {
        LocalDate getDay();

        long getSends();

        long getRecipients();
    }

    /** One status and its count, as returned by {@link #countByStatusInRange}. */
    interface StatusCountRow {
        NotificationStatus getStatus();

        long getTotal();
    }

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
