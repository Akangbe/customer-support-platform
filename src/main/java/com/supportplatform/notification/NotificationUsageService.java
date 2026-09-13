package com.supportplatform.notification;

import com.supportplatform.notification.dto.NotificationUsageResponse;
import com.supportplatform.user.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Answers "how much have we sent" for one tenant.
 *
 * <p>Read by two different callers with two different authorities — the
 * integrator through its API key, and an Owner or Admin through the
 * dashboard — so the counting lives here and neither controller knows how
 * it is done.
 *
 * <p>Everything is aggregated in the database. Loading rows to count them
 * would work today at a few hundred sends a day and stop working quietly
 * later, which is the worst way for it to stop working.
 */
@Service
public class NotificationUsageService {

    /**
     * The longest range that may be asked for. Not a performance guess: it
     * is a bound on how much work one request can ask the database to do,
     * so a mistyped date cannot turn into a full-table scan.
     */
    static final int MAX_RANGE_DAYS = 92;

    /** What a caller gets when it names no range at all. */
    static final int DEFAULT_RANGE_DAYS = 30;

    private final NotificationLogRepository notificationLogRepository;

    public NotificationUsageService(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    /**
     * Dashboard entry point. Owner and Admin only, matching who may manage
     * the API keys that produce this traffic — the same people, since usage
     * is what a key does and the kill switch is how they stop it.
     */
    @Transactional(readOnly = true)
    public NotificationUsageResponse forDashboard(UUID tenantId, UserRole actingRole, LocalDate from, LocalDate to) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can view notification usage");
        }
        return usage(tenantId, from, to);
    }

    /**
     * Integrator entry point. The tenant comes off the API key (Rule 3), so
     * a caller can only ever read its own usage — there is no parameter
     * through which it could name another.
     */
    @Transactional(readOnly = true)
    public NotificationUsageResponse forApiKey(UUID tenantId, LocalDate from, LocalDate to) {
        return usage(tenantId, from, to);
    }

    private NotificationUsageResponse usage(UUID tenantId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        validate(start, end);

        // Half-open: from 00:00:00 on the first day up to, but not
        // including, 00:00:00 on the day after the last. An inclusive upper
        // bound would either drop the final day's sends or double-count the
        // boundary depending on how it was written.
        Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<NotificationStatus, Long> counts = new EnumMap<>(NotificationStatus.class);
        for (NotificationLogRepository.StatusCountRow row
                : notificationLogRepository.countByStatusInRange(tenantId, fromInstant, toInstant)) {
            counts.put(row.getStatus(), row.getTotal());
        }

        List<NotificationUsageResponse.DailyUsage> daily =
                notificationLogRepository.findDailyUsage(tenantId, fromInstant, toInstant).stream()
                        .map(r -> new NotificationUsageResponse.DailyUsage(r.getDay(), r.getSends(), r.getRecipients()))
                        .toList();

        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        return new NotificationUsageResponse(
                start,
                end,
                total,
                notificationLogRepository.countDistinctRecipientsInRange(tenantId, fromInstant, toInstant),
                new NotificationUsageResponse.StatusBreakdown(
                        counts.getOrDefault(NotificationStatus.PENDING, 0L),
                        counts.getOrDefault(NotificationStatus.SENT, 0L),
                        counts.getOrDefault(NotificationStatus.DELIVERED, 0L),
                        counts.getOrDefault(NotificationStatus.READ, 0L),
                        counts.getOrDefault(NotificationStatus.FAILED, 0L)),
                daily);
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(BAD_REQUEST, "'from' must not be after 'to'.");
        }
        long days = Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Range is " + days + " days; the maximum is " + MAX_RANGE_DAYS + ". Ask for a shorter period.");
        }
    }
}
