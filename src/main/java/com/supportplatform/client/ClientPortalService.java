package com.supportplatform.client;

import com.supportplatform.apikey.ApiKeyRepository;
import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.client.dto.ClientMessageSearchRequest;
import com.supportplatform.client.dto.ClientPortalResponses;
import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationLogRepository;
import com.supportplatform.notification.NotificationStatus;
import com.supportplatform.notification.NotificationUsageService;
import com.supportplatform.notification.dto.NotificationUsageResponse;
import com.supportplatform.whatsapp.WhatsAppTemplate;
import com.supportplatform.whatsapp.WhatsAppTemplateRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a client can read about their own sending. Every method takes a
 * {@link ClientScope} and no account id, so a request has no way to name
 * another client.
 */
@Service
public class ClientPortalService {

    private static final String TIMEZONE = "UTC";
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationUsageService usageService;
    private final ClientMessageQuery messageQuery;
    private final WhatsAppTemplateRepository templateRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ClientPortalService(NotificationLogRepository notificationLogRepository,
                                 NotificationUsageService usageService, ClientMessageQuery messageQuery,
                                 WhatsAppTemplateRepository templateRepository, ApiKeyRepository apiKeyRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.notificationLogRepository = notificationLogRepository;
        this.usageService = usageService;
        this.messageQuery = messageQuery;
        this.templateRepository = templateRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public ClientPortalResponses.Overview overview(ClientScope scope, LocalDate from, LocalDate to) {
        NotificationUsageService.UsageRange range = usageService.resolveRange(from, to);
        UUID account = scope.clientAccountId();
        NotificationUsageResponse usage = usageService.forClientAccount(account, from, to);
        NotificationUsageResponse.StatusBreakdown status = usage.byStatus();
        Double deliveryRate = usage.totalSends() == 0 ? null
                : (double) (status.delivered() + status.read()) / usage.totalSends();

        return new ClientPortalResponses.Overview(range.start(), range.end(), TIMEZONE, usage.totalSends(),
                usage.distinctRecipients(), status, deliveryRate,
                failureGroups(account, range), repeatFailures(account, range));
    }

    @Transactional(readOnly = true)
    public ClientPortalResponses.DailyStats dailyStats(ClientScope scope, LocalDate from, LocalDate to) {
        NotificationUsageService.UsageRange range = usageService.resolveRange(from, to);
        List<ClientPortalResponses.DailyStat> days = notificationLogRepository
                .findDailyStatsForClient(scope.clientAccountId(), range.fromInstant(), range.toInstant()).stream()
                .map(row -> new ClientPortalResponses.DailyStat(row.getDay(), row.getSends(), row.getDelivered(),
                        row.getRead(), row.getFailed()))
                .toList();
        return new ClientPortalResponses.DailyStats(range.start(), range.end(), TIMEZONE, days);
    }

    @Transactional(readOnly = true)
    public ClientPortalResponses.MessagePage searchMessages(ClientScope scope, ClientMessageSearchRequest request) {
        NotificationUsageService.UsageRange range = usageService.resolveRange(request.from(), request.to());
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();

        // One extra row answers "is there another page" without counting.
        List<NotificationLog> rows = messageQuery.find(scope.clientAccountId(), new ClientMessageQuery.Criteria(
                range.fromInstant(), range.toInstant(), request.status(), request.template(),
                recipientForms(request.recipient()), request.cursor(), size + 1));

        boolean hasMore = rows.size() > size;
        List<NotificationLog> page = hasMore ? rows.subList(0, size) : rows;
        String nextCursor = hasMore ? ClientMessageQuery.encode(page.get(size - 1)) : null;
        return new ClientPortalResponses.MessagePage(page.stream().map(ClientPortalService::summary).toList(), nextCursor);
    }

    /** Not read-only: showing a full number writes the audit entry in the same transaction. */
    @Transactional
    public ClientPortalResponses.MessageDetail message(ClientScope scope, UUID messageId) {
        NotificationLog message = notificationLogRepository
                .findByIdAndClientAccountId(messageId, scope.clientAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

        eventPublisher.publishEvent(new AuditEvent(scope.tenantId(), scope.userId(), AuditAction.CLIENT_RECIPIENT_VIEWED,
                "NOTIFICATION", message.getId(), "Viewed recipient of message " + message.getId()));

        return new ClientPortalResponses.MessageDetail(message.getId(), message.getRecipient(),
                message.getTemplateName(), message.getLanguageCode(), message.getStatus(), failure(message),
                message.getMetaMessageId(), message.getIdempotencyKey(), message.getCreatedAt(), message.getUpdatedAt());
    }

    /**
     * Templates this client has sent, not the tenant's allowlist: on a shared
     * tenant that list includes templates belonging to the operator and to
     * other clients.
     */
    @Transactional(readOnly = true)
    public List<ClientPortalResponses.TemplateUsage> templates(ClientScope scope, LocalDate from, LocalDate to) {
        NotificationUsageService.UsageRange range = usageService.resolveRange(from, to);
        Map<String, String> statusByName = templateRepository.findByTenantIdOrderByNameAsc(scope.tenantId()).stream()
                .collect(Collectors.toMap(WhatsAppTemplate::getName, template -> template.getStatus().name()));

        return notificationLogRepository
                .findTemplateUsageForClient(scope.clientAccountId(), range.fromInstant(), range.toInstant()).stream()
                .map(row -> new ClientPortalResponses.TemplateUsage(row.getName(), statusByName.get(row.getName()),
                        row.getSends(), row.getDelivered(), row.getRead(), row.getFailed()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientPortalResponses.ApiKeySummary> apiKeys(ClientScope scope) {
        return apiKeyRepository.findByClientAccountIdOrderByCreatedAtDesc(scope.clientAccountId()).stream()
                .map(key -> new ClientPortalResponses.ApiKeySummary(key.getName(), key.getKeyId(), key.isActive(),
                        key.getRateLimit(), key.getLastUsedAt(), key.getCreatedAt()))
                .toList();
    }

    /** Several stored reasons explain as one, since Meta's text carries a unique trace id per failure. */
    private List<ClientPortalResponses.FailureGroup> failureGroups(UUID account,
                                                                   NotificationUsageService.UsageRange range) {
        Map<FailureExplanation, Long> counts = new LinkedHashMap<>();
        for (NotificationLogRepository.FailureReasonRow row : notificationLogRepository
                .findFailureReasonsForClient(account, range.fromInstant(), range.toInstant())) {
            counts.merge(FailureExplanation.from(row.getReason()), row.getTotal(), Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new ClientPortalResponses.FailureGroup(entry.getKey().code(), entry.getKey().category(),
                        entry.getKey().message(), entry.getValue()))
                .sorted(Comparator.comparingLong(ClientPortalResponses.FailureGroup::count).reversed())
                .toList();
    }

    private List<ClientPortalResponses.RepeatFailure> repeatFailures(UUID account,
                                                                     NotificationUsageService.UsageRange range) {
        return notificationLogRepository.findRepeatFailuresForClient(account, range.fromInstant(), range.toInstant())
                .stream()
                .map(row -> new ClientPortalResponses.RepeatFailure(mask(row.getRecipient()), row.getFailedCount(),
                        row.getLastMessageId()))
                .toList();
    }

    private static ClientPortalResponses.MessageSummary summary(NotificationLog message) {
        return new ClientPortalResponses.MessageSummary(message.getId(), mask(message.getRecipient()),
                message.getTemplateName(), message.getLanguageCode(), message.getStatus(), failure(message),
                message.getCreatedAt(), message.getUpdatedAt());
    }

    private static FailureExplanation failure(NotificationLog message) {
        return message.getStatus() == NotificationStatus.FAILED
                ? FailureExplanation.from(message.getFailureReason()) : null;
    }

    /** Numbers may be stored with or without the leading plus, depending on what the sender passed. */
    private static List<String> recipientForms(String recipient) {
        if (recipient == null) {
            return null;
        }
        String digits = recipient.startsWith("+") ? recipient.substring(1) : recipient;
        return List.of(digits, "+" + digits);
    }

    private static String mask(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "****";
        }
        return "****" + recipient.substring(recipient.length() - 4);
    }
}
