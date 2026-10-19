package com.supportplatform.email;

import com.supportplatform.whatsapp.DeliveryBlockedEvent;
import com.supportplatform.whatsapp.MetaBlockingError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells someone when Meta has stopped delivering for a reason only a
 * person can fix ({@link MetaBlockingError}), instead of letting the first
 * report come from a customer — which is what happened on 2026-09-05, when
 * an unpaid balance (131042) silently blocked every message for an unknown
 * number of hours.
 *
 * <p>Follows {@link InviteEmailListener}: {@code AFTER_COMMIT} so an alert
 * never describes a failure the transaction rolled back, and email
 * failures are swallowed to a warning, because a blocked SES send must not
 * fail the webhook processing that noticed the problem. The ERROR log is
 * written first and unconditionally for that reason — it is the signal
 * that survives SES being unconfigured or down, and the one to point a
 * log-based alert at.
 *
 * <p><strong>Deduplicated.</strong> While an account is blocked every
 * single message fails, so the naive version sends one email per failed
 * message — thousands of them, from the one outage. One alert per
 * (tenant, error) per {@link #COOLDOWN} instead, which is what makes this
 * safe to leave on.
 *
 * <p>Sent to the tenant's Owners and Admins and to the contact of every
 * active API key ({@link ApiKeyAlertRecipients#forAllKeys}): an integrator
 * sending through this number loses every message too, and should hear it
 * from us rather than from their own customers.
 */
@Component
public class DeliveryBlockedEmailListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryBlockedEmailListener.class);
    /** Long enough that an outage is one alert, short enough to re-raise if it is still broken tomorrow. */
    static final Duration COOLDOWN = Duration.ofHours(6);

    /**
     * Process-local, like {@code InMemoryRateLimiter} and for the same
     * single-instance deployment. The failure mode if that ever stops
     * holding is duplicate alert emails, not missed ones — the safe
     * direction for something whose whole job is to be noticed.
     */
    private final Map<String, Instant> lastAlerted = new ConcurrentHashMap<>();

    private final EmailGateway emailGateway;
    private final ApiKeyAlertRecipients alertRecipients;

    public DeliveryBlockedEmailListener(EmailGateway emailGateway, ApiKeyAlertRecipients alertRecipients) {
        this.emailGateway = emailGateway;
        this.alertRecipients = alertRecipients;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryBlocked(DeliveryBlockedEvent event) {
        if (!shouldAlert(event.tenantId(), event.error())) {
            return;
        }

        MetaBlockingError error = event.error();
        String scope = error.getScope() == MetaBlockingError.Scope.ACCOUNT
                ? "EVERY message from this WhatsApp number is blocked"
                : "sends using template '" + event.templateName() + "' are blocked";

        log.error("WHATSAPP DELIVERY BLOCKED for tenant {} - Meta error {} ({}): {} - {}",
                event.tenantId(), error.getCode(), scope, error.getExplanation(), event.failureReason());

        try {
            notifyEveryone(event, scope);
        } catch (Exception e) {
            log.warn("Could not email the delivery-blocked alert for tenant {}: {}", event.tenantId(), e.getMessage());
        }
    }

    /** @return true the first time this (tenant, error) is seen, then not again until the cooldown lapses. */
    private boolean shouldAlert(UUID tenantId, MetaBlockingError error) {
        String key = tenantId + ":" + error.getCode();
        Instant now = Instant.now();
        Instant previous = lastAlerted.get(key);
        if (previous != null && previous.isAfter(now.minus(COOLDOWN))) {
            return false;
        }
        // putIfAbsent/replace rather than put, so two threads racing on the
        // same outage still produce one alert rather than two.
        return previous == null
                ? lastAlerted.putIfAbsent(key, now) == null
                : lastAlerted.replace(key, previous, now);
    }

    private void notifyEveryone(DeliveryBlockedEvent event, String scope) {
        List<String> recipients = alertRecipients.forAllKeys(event.tenantId());
        if (recipients.isEmpty()) {
            log.warn("No one to alert about blocked WhatsApp delivery in tenant {}", event.tenantId());
            return;
        }

        MetaBlockingError error = event.error();
        String subject = "Action required: WhatsApp messages are not being delivered";
        String text = "Meta is accepting your messages and refusing to deliver them, so " + scope + ".\n\n"
                + "Meta error " + error.getCode() + ".\n\n"
                + error.getExplanation() + "\n\n"
                + "Meta's own words: " + event.failureReason() + "\n\n"
                + "Nothing will be delivered until this is resolved, and messages sent in the meantime are lost "
                + "rather than queued.";
        String html = "<p>Meta is accepting your messages and refusing to deliver them, so <strong>" + scope
                + "</strong>.</p>"
                + "<p>Meta error <strong>" + error.getCode() + "</strong>.</p>"
                + "<p>" + error.getExplanation() + "</p>"
                + "<p><em>Meta's own words:</em> " + event.failureReason() + "</p>"
                + "<p>Nothing will be delivered until this is resolved, and messages sent in the meantime are lost "
                + "rather than queued.</p>";

        for (String recipient : recipients) {
            emailGateway.send(recipient, subject, html, text);
        }
    }
}
