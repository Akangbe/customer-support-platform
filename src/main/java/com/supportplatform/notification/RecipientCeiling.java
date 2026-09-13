package com.supportplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounds how often one customer can be messaged with one template.
 *
 * <p>Nothing previously did. The per-key limit in
 * {@code ApiKeyAuthenticationFilter} counts requests, not recipients, so a
 * caller looping over a single customer stays far inside 60/minute while
 * that customer's phone fills with identical messages. Between 2026-09-01
 * and 2026-09-12 the worst case was 38 sends of the same template to one
 * number in 18 minutes.
 *
 * <h2>Why the count comes from Postgres, not the {@code RateLimiter}</h2>
 * <p>{@link com.supportplatform.apikey.InMemoryRateLimiter} keeps its
 * counters in the process, which is correct for what it does. This ceiling
 * cannot: the service runs on a host that spins down when idle and cold
 * starts afterwards, so an in-process window resets to zero every time the
 * instance wakes — failing open at precisely the moment a queued burst
 * arrives at a freshly started server. A counted window over an indexed
 * column survives restarts, and at this volume the query is free
 * ({@code idx_notification_log_recipient_window}, V14).
 *
 * <h2>Observe mode</h2>
 * <p>Ships as {@code observe}: the ceiling is evaluated and every breach
 * logged, but nothing is blocked. A throttle enforced against live traffic
 * before anyone has confirmed what it would catch can silence a real
 * notification, and an unsent notification is a worse failure than a
 * duplicate one. Flip to {@code enforce} once the log shows it is catching
 * only what it should.
 */
@Component
public class RecipientCeiling {

    private static final Logger log = LoggerFactory.getLogger(RecipientCeiling.class);

    /** Blocks nothing; logs what it would have blocked. */
    static final String OBSERVE = "observe";

    private final NotificationLogRepository notificationLogRepository;
    private final String mode;
    private final int maxPerWindow;
    private final Duration window;

    public RecipientCeiling(NotificationLogRepository notificationLogRepository,
                              @Value("${app.notifications.recipient-ceiling.mode:observe}") String mode,
                              @Value("${app.notifications.recipient-ceiling.max-per-window:10}") int maxPerWindow,
                              @Value("${app.notifications.recipient-ceiling.window:PT1H}") Duration window) {
        this.notificationLogRepository = notificationLogRepository;
        this.mode = mode;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    /**
     * @throws RecipientCeilingExceededException when the ceiling is breached and the mode is {@code enforce}
     */
    public void check(UUID tenantId, String recipient, String templateName, Instant now) {
        Instant windowStart = now.minus(window);
        long alreadySent = notificationLogRepository.countInWindow(tenantId, recipient, templateName, windowStart);

        if (alreadySent < maxPerWindow) {
            return;
        }

        // The recipient is masked for the same reason it is masked in
        // NotificationSendService: these lines ship to log sinks, and the
        // full number lives in the database column instead.
        String masked = mask(recipient);

        if (OBSERVE.equalsIgnoreCase(mode)) {
            log.warn("RECIPIENT CEILING (observe, not blocked): recipient={} template='{}' tenant={} has had {} "
                            + "sends in the last {} — at or above the limit of {}. This send would be rejected "
                            + "in enforce mode.",
                    masked, templateName, tenantId, alreadySent, window, maxPerWindow);
            return;
        }

        long retryAfterSeconds = retryAfterSeconds(tenantId, recipient, templateName, windowStart, now);
        log.warn("RECIPIENT CEILING (enforced, rejected): recipient={} template='{}' tenant={} has had {} sends "
                        + "in the last {}, limit {}. Retry in {}s.",
                masked, templateName, tenantId, alreadySent, window, maxPerWindow, retryAfterSeconds);

        throw new RecipientCeilingExceededException(
                "This recipient has already received " + alreadySent + " '" + templateName + "' notifications in the "
                        + "last " + window.toMinutes() + " minutes, which is at the limit of " + maxPerWindow
                        + ". Retry in " + retryAfterSeconds + " seconds.",
                retryAfterSeconds);
    }

    /**
     * How long until the oldest send in the window ages out, which is the
     * first moment a further send could be accepted. A concrete number
     * rather than a fixed guess, so a caller honouring {@code Retry-After}
     * waits exactly as long as it has to and no longer.
     */
    private long retryAfterSeconds(UUID tenantId, String recipient, String templateName,
                                     Instant windowStart, Instant now) {
        Optional<Instant> oldest =
                notificationLogRepository.findOldestInWindow(tenantId, recipient, templateName, windowStart);
        if (oldest.isEmpty()) {
            // The row aged out between the count and this lookup. Nothing is
            // blocking any more; ask for the smallest honest retry rather
            // than reporting a window the caller no longer has to wait out.
            return 1L;
        }
        long seconds = Duration.between(now, oldest.get().plus(window)).toSeconds();
        return Math.max(seconds, 1L);
    }

    private static String mask(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "****";
        }
        return "****" + recipient.substring(recipient.length() - 4);
    }
}
