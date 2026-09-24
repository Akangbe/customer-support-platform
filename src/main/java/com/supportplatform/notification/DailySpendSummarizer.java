package com.supportplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells an integrator, once per UTC day, what their key sent the day
 * before and what the delivered messages cost.
 *
 * <p>Trustpady kept being surprised by the accumulated fee: the cost was
 * real every day but only ever mentioned once it had added up. A figure
 * mailed each morning makes the running total something they watch rather
 * than something they are told.
 *
 * <p>Triggered from the send path, like {@link DailyUsageMonitor} and for
 * the same reason: a scheduled job on a service that spins down when idle
 * runs only when something else has woken it. The first send of a new day
 * is the moment the key is demonstrably active and the day before is
 * demonstrably over.
 *
 * <p>Catches up rather than looking only at yesterday. A key that sends
 * nothing on Tuesday would otherwise never have Monday summarized, because
 * Wednesday's first send would look only at the empty Tuesday.
 */
@Component
public class DailySpendSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DailySpendSummarizer.class);

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationDailySummaryRepository summaryRepository;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final BigDecimal pricePerDelivered;
    private final String currency;
    private final int lookbackDays;

    /**
     * The last day each key was checked on. Only a shortcut, so every send
     * after the first of the day costs a map lookup instead of a query; the
     * ledger is the real guard, and losing this map to a cold start costs
     * one extra query, never a duplicate mail.
     */
    private final Map<UUID, LocalDate> checkedOn = new ConcurrentHashMap<>();

    public DailySpendSummarizer(NotificationLogRepository notificationLogRepository,
                                  NotificationDailySummaryRepository summaryRepository,
                                  ApplicationEventPublisher events,
                                  @Value("${app.notifications.daily-summary.enabled:true}") boolean enabled,
                                  @Value("${app.notifications.daily-summary.price-per-delivered:0.01}")
                                  BigDecimal pricePerDelivered,
                                  @Value("${app.notifications.daily-summary.currency:AUD}") String currency,
                                  @Value("${app.notifications.daily-summary.lookback-days:7}") int lookbackDays) {
        this.notificationLogRepository = notificationLogRepository;
        this.summaryRepository = summaryRepository;
        this.events = events;
        this.enabled = enabled;
        this.pricePerDelivered = pricePerDelivered;
        this.currency = currency;
        this.lookbackDays = lookbackDays;
    }

    /**
     * Called after a send has been recorded. Never throws: a summary that
     * can fail a send turns a reporting problem into an outage.
     */
    public void recordSend(UUID tenantId, UUID apiKeyId) {
        if (!enabled || apiKeyId == null) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(checkedOn.get(apiKeyId))) {
            return;
        }
        try {
            summarizeFinishedDays(tenantId, apiKeyId, today);
            checkedOn.put(apiKeyId, today);
        } catch (Exception e) {
            log.warn("Daily spend summary failed for key {} in tenant {}: {}", apiKeyId, tenantId, e.getMessage());
        }
    }

    /**
     * Forgets which keys were checked today — exactly what a cold start
     * does. For tests, which move rows into "yesterday" after sending them
     * and cannot otherwise make the process see a new day.
     */
    void forgetChecks() {
        checkedOn.clear();
    }

    private void summarizeFinishedDays(UUID tenantId, UUID apiKeyId, LocalDate today) {
        LocalDate from = today.minusDays(lookbackDays);
        Set<LocalDate> done = new HashSet<>(summaryRepository.findSummarizedDays(apiKeyId, from, today));

        // Today is excluded by the upper bound: it is not over, so any figure
        // for it would be wrong by the time it was read.
        for (NotificationLogRepository.DailyOutcomeRow row : notificationLogRepository.findDailyOutcomesForKey(
                tenantId, apiKeyId,
                from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                today.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            if (done.contains(row.getDay())) {
                continue;
            }
            BigDecimal cost = pricePerDelivered.multiply(BigDecimal.valueOf(row.getDelivered()));
            events.publishEvent(new DailySpendSummaryEvent(tenantId, apiKeyId, row.getDay(),
                    row.getSends(), row.getDelivered(), row.getFailed(), pricePerDelivered, cost, currency));
        }
    }
}
