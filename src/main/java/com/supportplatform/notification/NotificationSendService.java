package com.supportplatform.notification;

import com.supportplatform.apikey.ApiKeyPrincipal;
import com.supportplatform.notification.dto.SendNotificationRequest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppConnection;
import com.supportplatform.whatsapp.WhatsAppConnectionRepository;
import com.supportplatform.whatsapp.WhatsAppGateway;
import com.supportplatform.whatsapp.WhatsAppTemplate;
import com.supportplatform.whatsapp.WhatsAppTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * Relays an approved template message to a tenant's own WhatsApp number on
 * behalf of that tenant's backend.
 *
 * <p>Every tenant-scoped read here uses {@code principal.tenantId()},
 * which came off the API key row (Rule 3) — the request body has no say in
 * which tenant is acting, and so no say in which WhatsApp number or token
 * gets used. The token itself is fetched, decrypted and handed to the
 * gateway inside this process; it is never returned, echoed or logged.
 *
 * <p>Deliberately not {@code @Transactional}: the log row must survive the
 * failure path. Each {@code save} runs in its own transaction, so writing
 * "FAILED" and then throwing does not roll that record back.
 *
 * <h2>Idempotency (V15)</h2>
 * <p>The Graph call is preceded by a reservation row. When the caller
 * supplied an {@code Idempotency-Key}, that row carries it and a unique
 * index makes every concurrent or later retry collide and be answered from
 * the original instead of reaching Meta. When the caller supplied nothing,
 * the row is still claimed — one code path, and a PENDING row left behind
 * is evidence a request died mid-send — but no deduplication happens, and
 * none is invented. See {@link #normaliseKey}.
 */
@Service
public class NotificationSendService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendService.class);

    private final WhatsAppConnectionRepository connectionRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final WhatsAppGateway gateway;
    private final WhatsAppTemplateService templateService;
    private final RecipientCeiling recipientCeiling;
    private final DailyUsageMonitor dailyUsageMonitor;

    public NotificationSendService(WhatsAppConnectionRepository connectionRepository,
                                     NotificationLogRepository notificationLogRepository,
                                     WhatsAppGateway gateway, WhatsAppTemplateService templateService,
                                     RecipientCeiling recipientCeiling, DailyUsageMonitor dailyUsageMonitor) {
        this.connectionRepository = connectionRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.gateway = gateway;
        this.templateService = templateService;
        this.recipientCeiling = recipientCeiling;
        this.dailyUsageMonitor = dailyUsageMonitor;
    }

    public SendOutcome send(ApiKeyPrincipal principal, SendNotificationRequest request, String idempotencyKey) {
        // ENTRY. The recipient is logged as a masked suffix, not in full: this
        // line is the one emitted on every request, and a log aggregator full
        // of complete customer phone numbers is a data-protection problem the
        // privacy policy would have to answer for.
        log.info("SEND ENTRY tenant={} key={} template='{}' recipient={}",
                principal.tenantId(), principal.keyId(), request.templateName(), mask(request.recipient()));

        WhatsAppConnection connection = connectionRepository.findByTenantId(principal.tenantId())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        "WhatsApp is not connected for this tenant. Connect a number before sending notifications."));

        // Checked before the Meta call and before any log row: a template the
        // tenant doesn't own is a rejected request, not a failed send, so it
        // must not land in notification_log as a delivery failure.
        requireSendableTemplate(principal.tenantId(), request.templateName());

        // Same reasoning, same placement: a ceiling breach is a rejected
        // request. It must not write a FAILED row, or every usage figure
        // derived from notification_log would count messages that were never
        // sent and never billed.
        recipientCeiling.check(principal.tenantId(), request.recipient(), request.templateName(), Instant.now());

        String key = normaliseKey(idempotencyKey);
        if (key != null) {
            Optional<NotificationLog> prior =
                    notificationLogRepository.findByTenantIdAndIdempotencyKey(principal.tenantId(), key);
            if (prior.isPresent()) {
                return replay(prior.get(), principal, key, "prior send");
            }
        }

        // Claim the row before calling Meta. saveAndFlush, not save: the
        // INSERT has to reach the database now so the unique index can reject
        // a concurrent retry. A deferred flush would let both requests sail
        // past this point and both call Meta.
        NotificationLog reserved;
        try {
            reserved = notificationLogRepository.saveAndFlush(NotificationLog.reserve(principal.tenantId(),
                    principal.apiKeyId(), request.recipient(), request.templateName(), request.languageCode(), key));
        } catch (DataIntegrityViolationException e) {
            // Lost the race: another in-flight request claimed this key
            // between the lookup above and this insert.
            NotificationLog winner = notificationLogRepository
                    .findByTenantIdAndIdempotencyKey(principal.tenantId(), key)
                    .orElseThrow(() -> e);
            return replay(winner, principal, key, "concurrent retry");
        }

        // CALL. Deliberately immediately before the Graph API call rather than
        // after: if the process is killed mid-call (a Render instance being
        // spun down, say) this is the last line written, and its absence or
        // presence is what says whether Meta was ever contacted — which is
        // exactly the question a duplicate investigation turns on.
        log.info("SEND CALLING META phone_number_id={} template='{}' recipient={} notification={}",
                connection.getPhoneNumberId(), request.templateName(), mask(request.recipient()), reserved.getId());

        SendResult result = gateway.sendTemplate(connection, request.recipient(), request.templateName(),
                request.languageCode(), request.bodyParams(), request.buttonUrlParam());

        if (result.success()) {
            reserved.markSent(result.waMessageId());
            NotificationLog sent = notificationLogRepository.save(reserved);
            // SETTLED.
            log.info("SEND LOGGED notification={} status=SENT wamid={} tenant={} key={} template='{}'",
                    sent.getId(), result.waMessageId(), principal.tenantId(), principal.keyId(),
                    request.templateName());
            // After the row exists, so the count it takes includes this send.
            // Never throws — monitoring that can fail a send turns an
            // observability problem into an outage.
            dailyUsageMonitor.recordSend(principal.tenantId(), principal.apiKeyId());
            return SendOutcome.sent(sent);
        }

        reserved.markSendFailed(result.errorDetail());
        NotificationLog failed = notificationLogRepository.save(reserved);
        // Meta's detail stays here, on our side of the boundary.
        log.warn("SEND LOGGED notification={} status=FAILED tenant={} key={} template='{}' detail={}",
                failed.getId(), principal.tenantId(), principal.keyId(), request.templateName(), result.errorDetail());
        throw new NotificationDeliveryException(failed.getId());
    }

    /**
     * Answers a repeated key from the record instead of sending again.
     *
     * <p>A recorded failure is replayed <em>as a failure</em>, the same
     * status the caller got the first time. The alternative — reporting
     * success on a replay of something that failed — would have a caller
     * believe a customer was notified when nobody was. A genuine retry after
     * a failure is a new attempt and takes a new key.
     */
    private SendOutcome replay(NotificationLog prior, ApiKeyPrincipal principal, String key, String cause) {
        log.warn("IDEMPOTENT REPLAY: {} for tenant {} via key {} matched notification {} (status {}); "
                        + "no WhatsApp message sent and nothing billed.",
                cause, principal.tenantId(), principal.keyId(), prior.getId(), prior.getStatus());

        if (prior.getStatus() == NotificationStatus.FAILED) {
            throw new NotificationDeliveryException(prior.getId());
        }
        return SendOutcome.replayed(prior);
    }

    /**
     * The key is taken as given, never inferred. The single template in use
     * carries no parameters, so two requests for different business events
     * are byte-identical — a key derived from the body could not tell a
     * retry from a second real notification, and would silently drop the
     * latter. Only the caller knows; absent the header, there is no
     * deduplication and that is the correct behaviour.
     */
    private static String normaliseKey(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return null;
        }
        String trimmed = supplied.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /**
     * Keeps enough of a phone number to correlate log lines with each other
     * and with a {@code notification_log} row, without writing the whole
     * number into every log sink that ships these lines onward. The full
     * value is still in the database column, where access is controlled.
     */
    private static String mask(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "****";
        }
        return "****" + recipient.substring(recipient.length() - 4);
    }

    /**
     * The allowlist gate (whatsapp-domain.md §8). Meta remains the real
     * authority — it can pause a template without telling us — so this
     * cannot guarantee a send succeeds. What it does guarantee is that a
     * tenant cannot send a template belonging to someone else's WABA, and
     * that the everyday mistakes (typo, not approved yet) come back as a
     * clear 4xx instead of a relayed Graph API error code.
     */
    private void requireSendableTemplate(UUID tenantId, String templateName) {
        WhatsAppTemplate template = templateService.findForTenant(tenantId, templateName)
                .orElseThrow(() -> TemplateNotAllowedException.unknown(templateName));

        if (!template.getStatus().isSendable()) {
            throw TemplateNotAllowedException.notApproved(templateName, template.getStatus());
        }
    }
}
