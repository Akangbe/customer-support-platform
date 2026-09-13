package com.supportplatform.notification.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * How much has been sent, over a range of whole UTC days.
 *
 * <p>Both {@code totalSends} and {@code distinctRecipients} are present
 * because the gap between them is the number worth watching. A week where
 * sends climb and recipients do not is not a busier week; it is the same
 * people being messaged more often, which is what the 2026-09-11 spike
 * turned out to be.
 *
 * <p>{@code failed} is broken out separately for a practical reason:
 * WhatsApp does not charge for a message it refused, so a total that folds
 * failures in overstates the bill.
 */
public record NotificationUsageResponse(
        LocalDate from,
        LocalDate to,
        long totalSends,
        long distinctRecipients,
        StatusBreakdown byStatus,
        List<DailyUsage> daily
) {

    /**
     * @param pending claimed but never settled — the request died between
     *                contacting Meta and recording the answer, so whether
     *                the message went out is genuinely unknown
     */
    public record StatusBreakdown(long pending, long sent, long delivered, long read, long failed) {
    }

    public record DailyUsage(LocalDate date, long sends, long recipients) {
    }
}
