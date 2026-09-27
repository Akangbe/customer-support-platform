package com.supportplatform.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

public interface NotificationUsageAlertRepository extends JpaRepository<NotificationUsageAlert, UUID> {

    /**
     * The cheap path: almost every send after a threshold is crossed asks
     * this and is told the alert has already gone out. The unique constraint
     * remains the real guard — this only keeps the common case from being an
     * exception.
     *
     * <p>A row with no key is one written before V19, when the count was
     * tenant-wide; it still counts as alerted for its own day, so the day
     * the per-key count shipped did not announce its thresholds twice.
     */
    @Query("""
            SELECT count(a) > 0 FROM NotificationUsageAlert a
             WHERE a.tenantId = :tenantId
               AND a.periodStart = :periodStart
               AND a.threshold = :threshold
               AND (a.apiKeyId = :apiKeyId OR a.apiKeyId IS NULL)
            """)
    boolean alreadyAlerted(@Param("tenantId") UUID tenantId, @Param("apiKeyId") UUID apiKeyId,
                             @Param("periodStart") LocalDate periodStart, @Param("threshold") int threshold);
}
