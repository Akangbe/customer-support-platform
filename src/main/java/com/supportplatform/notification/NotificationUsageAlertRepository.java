package com.supportplatform.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

public interface NotificationUsageAlertRepository extends JpaRepository<NotificationUsageAlert, UUID> {

    /**
     * The cheap path: almost every send after a threshold is crossed asks
     * this and is told the alert has already gone out. The unique constraint
     * remains the real guard — this only keeps the common case from being an
     * exception.
     */
    boolean existsByTenantIdAndPeriodStartAndThreshold(UUID tenantId, LocalDate periodStart, int threshold);
}
