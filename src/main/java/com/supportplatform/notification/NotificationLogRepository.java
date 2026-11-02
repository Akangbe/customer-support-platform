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

    Optional<NotificationLog> findByIdAndClientAccountId(UUID id, UUID clientAccountId);

    Optional<NotificationLog> findByClientAccountIdAndMetaMessageId(UUID clientAccountId, String metaMessageId);

    /** The per-client totals behind {@code findDailyUsage}: clients sharing a tenant must not see each other's volume. */
    @Query(value = """
            SELECT (n.created_at AT TIME ZONE 'UTC')::date AS day,
                   count(*)                    AS sends,
                   count(DISTINCT n.recipient) AS recipients
              FROM notification_log n
             WHERE n.client_account_id = :clientAccountId
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<DailyUsageRow> findDailyUsageForClient(@Param("clientAccountId") UUID clientAccountId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to);

    @Query("""
            SELECT n.status AS status, count(n) AS total
              FROM NotificationLog n
             WHERE n.clientAccountId = :clientAccountId
               AND n.createdAt >= :from
               AND n.createdAt <  :to
             GROUP BY n.status
            """)
    List<StatusCountRow> countByStatusInRangeForClient(@Param("clientAccountId") UUID clientAccountId,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    @Query("""
            SELECT count(DISTINCT n.recipient)
              FROM NotificationLog n
             WHERE n.clientAccountId = :clientAccountId
               AND n.createdAt >= :from
               AND n.createdAt <  :to
            """)
    long countDistinctRecipientsInRangeForClient(@Param("clientAccountId") UUID clientAccountId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to);

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
     * Everything this key has sent since {@code from} — the running day
     * total behind the volume thresholds. Counts every status, because a
     * runaway caller hammering a template Meta keeps refusing is exactly the
     * case worth being told about.
     *
     * <p>Per key rather than per tenant (V19): the mail goes to that key's
     * integrator, and a tenant can carry several integrators' keys.
     * Tenant-scoped as well, so a key id can never count rows outside its
     * own tenant.
     */
    @Query("""
            SELECT count(n) FROM NotificationLog n
             WHERE n.tenantId = :tenantId
               AND n.apiKeyId = :apiKeyId
               AND n.createdAt >= :from
            """)
    long countSinceForKey(@Param("tenantId") UUID tenantId, @Param("apiKeyId") UUID apiKeyId,
                           @Param("from") Instant from);

    /**
     * One key's per-day outcome split, behind the daily spend summary.
     * Scoped by key as well as tenant: a tenant can carry several
     * integrators' keys, and each is told only about its own traffic.
     *
     * <p>DELIVERED and READ are counted together because a read message was
     * necessarily delivered, and delivery is what Meta charges for.
     */
    @Query(value = """
            SELECT (n.created_at AT TIME ZONE 'UTC')::date                         AS day,
                   count(*)                                                      AS sends,
                   count(*) FILTER (WHERE n.status IN ('DELIVERED', 'READ'))     AS delivered,
                   count(*) FILTER (WHERE n.status = 'FAILED')                   AS failed
              FROM notification_log n
             WHERE n.tenant_id  = :tenantId
               AND n.api_key_id = :apiKeyId
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<DailyOutcomeRow> findDailyOutcomesForKey(@Param("tenantId") UUID tenantId,
                                                    @Param("apiKeyId") UUID apiKeyId,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);

    /** One client's outcomes per UTC day. {@code delivered} includes read, since a read message was delivered. */
    @Query(value = """
            SELECT (n.created_at AT TIME ZONE 'UTC')::date                     AS day,
                   count(*)                                                  AS sends,
                   count(*) FILTER (WHERE n.status IN ('DELIVERED', 'READ')) AS delivered,
                   count(*) FILTER (WHERE n.status = 'READ')                 AS read,
                   count(*) FILTER (WHERE n.status = 'FAILED')               AS failed
              FROM notification_log n
             WHERE n.client_account_id = :clientAccountId
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<ClientDailyStatRow> findDailyStatsForClient(@Param("clientAccountId") UUID clientAccountId,
                                                       @Param("from") Instant from,
                                                       @Param("to") Instant to);

    /** Stored reasons with their counts. Explained elsewhere, because the raw text must not reach a client. */
    @Query("""
            SELECT n.failureReason AS reason, count(n) AS total
              FROM NotificationLog n
             WHERE n.clientAccountId = :clientAccountId
               AND n.status = com.supportplatform.notification.NotificationStatus.FAILED
               AND n.createdAt >= :from
               AND n.createdAt <  :to
             GROUP BY n.failureReason
            """)
    List<FailureReasonRow> findFailureReasonsForClient(@Param("clientAccountId") UUID clientAccountId,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    /** Numbers that failed more than once, worst first: the ones a client can act on. */
    @Query(value = """
            SELECT n.recipient                                 AS recipient,
                   count(*)                                    AS failedCount,
                   (array_agg(n.id ORDER BY n.created_at DESC))[1] AS lastMessageId
              FROM notification_log n
             WHERE n.client_account_id = :clientAccountId
               AND n.status = 'FAILED'
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY n.recipient
            HAVING count(*) > 1
             ORDER BY 2 DESC, 1
             LIMIT 10
            """, nativeQuery = true)
    List<RepeatFailureRow> findRepeatFailuresForClient(@Param("clientAccountId") UUID clientAccountId,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    /** The templates this client actually sent, which is not the same as the tenant's allowlist. */
    @Query(value = """
            SELECT n.template_name                                         AS name,
                   count(*)                                                AS sends,
                   count(*) FILTER (WHERE n.status IN ('DELIVERED', 'READ')) AS delivered,
                   count(*) FILTER (WHERE n.status = 'READ')               AS read,
                   count(*) FILTER (WHERE n.status = 'FAILED')             AS failed
              FROM notification_log n
             WHERE n.client_account_id = :clientAccountId
               AND n.created_at >= :from
               AND n.created_at <  :to
             GROUP BY n.template_name
             ORDER BY 2 DESC, 1
            """, nativeQuery = true)
    List<ClientTemplateRow> findTemplateUsageForClient(@Param("clientAccountId") UUID clientAccountId,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    interface ClientDailyStatRow {
        LocalDate getDay();

        long getSends();

        long getDelivered();

        long getRead();

        long getFailed();
    }

    interface FailureReasonRow {
        String getReason();

        long getTotal();
    }

    interface RepeatFailureRow {
        String getRecipient();

        long getFailedCount();

        UUID getLastMessageId();
    }

    interface ClientTemplateRow {
        String getName();

        long getSends();

        long getDelivered();

        long getRead();

        long getFailed();
    }

    /** One day's outcomes for one key, as returned by {@link #findDailyOutcomesForKey}. */
    interface DailyOutcomeRow {
        LocalDate getDay();

        long getSends();

        long getDelivered();

        long getFailed();
    }

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
     * The idempotency lookup (unique index in V20). At most one row can
     * hold a given key for a client, so this is the whole replay decision.
     */
    Optional<NotificationLog> findByClientAccountIdAndIdempotencyKey(UUID clientAccountId, String idempotencyKey);

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
