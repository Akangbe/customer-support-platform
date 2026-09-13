package com.supportplatform.notification;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One tenant has sent enough notifications today to cross a configured
 * threshold.
 *
 * <p>Carries {@code apiKeyId} so the alert can also reach whoever is
 * behind the key, not only the tenant's own Owners and Admins: the traffic
 * is usually an integrator's, and telling them the same morning is faster
 * than forwarding an email by hand.
 *
 * @param sendsToday the count at the moment the threshold was crossed, which
 *                   may already be higher by the time the mail is read
 */
public record DailyUsageThresholdEvent(UUID tenantId,
                                         UUID apiKeyId,
                                         LocalDate periodStart,
                                         int threshold,
                                         long sendsToday) {
}
