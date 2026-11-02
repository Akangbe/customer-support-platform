package com.supportplatform.client.dto;

import com.supportplatform.client.FailureExplanation;
import com.supportplatform.notification.NotificationStatus;
import com.supportplatform.notification.dto.NotificationUsageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the client portal returns. Dates are whole UTC days. Nothing here
 * carries a tenant id, a WhatsApp account id or Meta's raw failure text.
 */
public final class ClientPortalResponses {

    private ClientPortalResponses() {
    }

    /**
     * @param deliveryRate delivered or read as a share of everything sent, from
     *                     0 to 1; null when nothing was sent
     */
    public record Overview(LocalDate from, LocalDate to, String timezone, long totalSends, long distinctRecipients,
                           NotificationUsageResponse.StatusBreakdown byStatus, Double deliveryRate,
                           List<FailureGroup> failures, List<RepeatFailure> repeatFailures) {
    }

    public record FailureGroup(String code, FailureExplanation.Category category, String message, long count) {
    }

    /** A masked number that keeps failing. {@code lastMessageId} opens the message for the full number. */
    public record RepeatFailure(String recipient, long failedCount, UUID lastMessageId) {
    }

    /** @param delivered includes messages that were also read, since a read message was delivered */
    public record DailyStat(LocalDate date, long sends, long delivered, long read, long failed) {
    }

    public record DailyStats(LocalDate from, LocalDate to, String timezone, List<DailyStat> days) {
    }

    /** @param nextCursor pass back for the next page; null on the last one */
    public record MessagePage(List<MessageSummary> items, String nextCursor) {
    }

    /** @param recipient masked to its last four digits */
    public record MessageSummary(UUID id, String recipient, String templateName, String languageCode,
                                 NotificationStatus status, FailureExplanation failure, Instant createdAt,
                                 Instant lastUpdatedAt) {
    }

    /** @param recipient the full number; opening a message is recorded in the audit log */
    public record MessageDetail(UUID id, String recipient, String templateName, String languageCode,
                                NotificationStatus status, FailureExplanation failure, String metaMessageId,
                                String idempotencyKey, Instant createdAt, Instant lastUpdatedAt) {
    }

    /** @param status the template's current status; null if it has since been removed */
    public record TemplateUsage(String name, String status, long sends, long delivered, long read, long failed) {
    }

    public record ApiKeySummary(String name, String keyId, boolean active, int rateLimit, Instant lastUsedAt,
                                Instant createdAt) {
    }
}
