package com.supportplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Watches how much a tenant has sent today and raises an event the first
 * time each configured threshold is crossed.
 *
 * <p>Thresholds escalate rather than repeat: 100 is "a busy morning, worth
 * knowing", 500 is "something is wrong", 1000 is "stop and look now". A
 * single threshold would either be too low to mean anything or too high to
 * arrive in time — on 2026-09-11 the day ended at 657 against a baseline
 * of 200-300, so a single alarm set at 1000 would never have fired and one
 * set at 100 would have fired on ordinary days too.
 *
 * <p>Counting happens on the send path rather than on a schedule. A
 * scheduled sweep on a service that spins down when idle runs only when
 * something else has already woken it, which is precisely not when a burst
 * is arriving.
 */
@Component
public class DailyUsageMonitor {

    private static final Logger log = LoggerFactory.getLogger(DailyUsageMonitor.class);

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationUsageAlertRepository alertRepository;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final List<Integer> thresholds;

    public DailyUsageMonitor(NotificationLogRepository notificationLogRepository,
                               NotificationUsageAlertRepository alertRepository,
                               ApplicationEventPublisher events,
                               @Value("${app.notifications.usage-alert.enabled:true}") boolean enabled,
                               @Value("${app.notifications.usage-alert.thresholds:100,500,1000}")
                               List<Integer> thresholds) {
        this.notificationLogRepository = notificationLogRepository;
        this.alertRepository = alertRepository;
        this.events = events;
        this.enabled = enabled;
        // Descending, so the loudest threshold a day has reached is the one
        // announced. Crossing 500 in one burst should not report "100".
        this.thresholds = thresholds.stream().sorted((a, b) -> Integer.compare(b, a)).toList();
    }

    /**
     * Called after a send has been recorded. Never throws: monitoring that
     * can fail a send is worse than no monitoring, because it turns an
     * observability problem into an outage.
     */
    public void recordSend(UUID tenantId, UUID apiKeyId) {
        if (!enabled || thresholds.isEmpty()) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
            long sendsToday = notificationLogRepository.countSinceForTenant(tenantId, dayStart);

            for (int threshold : thresholds) {
                if (sendsToday < threshold) {
                    continue;
                }
                // The cheap check first: after a threshold is crossed, almost
                // every later send asks this and is told the alert already
                // went. The unique constraint is still the real guard —
                // this only keeps the common case from being an exception.
                if (!alertRepository.existsByTenantIdAndPeriodStartAndThreshold(tenantId, today, threshold)) {
                    events.publishEvent(
                            new DailyUsageThresholdEvent(tenantId, apiKeyId, today, threshold, sendsToday));
                }
                // Only the highest threshold reached is announced.
                return;
            }
        } catch (Exception e) {
            log.warn("Daily usage check failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
