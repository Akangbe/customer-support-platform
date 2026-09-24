package com.supportplatform.email;

import com.supportplatform.notification.DailySpendSummaryEvent;
import com.supportplatform.notification.NotificationDailySummary;
import com.supportplatform.notification.NotificationDailySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Mails the previous day's sends and estimated cost for one API key to the
 * key's contact and the tenant's Owners and Admins.
 *
 * <p>Same shape as {@link UsageThresholdEmailListener}: the ledger insert is
 * the claim, the log line is written unconditionally, and a mail failure is
 * swallowed to a warning so it can never fail the send that triggered it.
 *
 * <p>Ships reaching the tenant's Owners and Admins only, the way the
 * recipient ceiling shipped in observe mode: the operator reads a few real
 * summaries before the integrator is added. A day summarized in that mode
 * is not summarized again once the contact is added, so switching over
 * never delivers a backlog.
 */
@Component
public class DailySpendSummaryEmailListener {

    private static final Logger log = LoggerFactory.getLogger(DailySpendSummaryEmailListener.class);

    private final EmailGateway emailGateway;
    private final ApiKeyAlertRecipients alertRecipients;
    private final NotificationDailySummaryRepository summaryRepository;
    private final boolean includeKeyContact;

    public DailySpendSummaryEmailListener(EmailGateway emailGateway, ApiKeyAlertRecipients alertRecipients,
                                            NotificationDailySummaryRepository summaryRepository,
                                            @Value("${app.notifications.daily-summary.recipients:owners}")
                                            String recipients) {
        this.emailGateway = emailGateway;
        this.alertRecipients = alertRecipients;
        this.summaryRepository = summaryRepository;
        this.includeKeyContact = "all".equalsIgnoreCase(recipients.trim());
    }

    /** {@code fallbackExecution} for the same reason as the volume alert: the send path has no transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDayFinished(DailySpendSummaryEvent event) {
        if (!claim(event)) {
            return;
        }

        log.info("NOTIFICATION DAILY SUMMARY: key {} in tenant {} on {} (UTC): sent={} delivered={} failed={} cost={} {}",
                event.apiKeyId(), event.tenantId(), event.periodStart(), event.sends(), event.delivered(),
                event.failed(), money(event.cost()), event.currency());

        try {
            notify(event);
        } catch (Exception e) {
            log.warn("Could not email the daily summary for key {} in tenant {}: {}",
                    event.apiKeyId(), event.tenantId(), e.getMessage());
        }
    }

    /** The insert is the claim: two instances racing on the same day both reach here and the unique constraint settles it. */
    private boolean claim(DailySpendSummaryEvent event) {
        try {
            summaryRepository.save(new NotificationDailySummary(event));
            return true;
        } catch (DataIntegrityViolationException alreadySummarized) {
            return false;
        }
    }

    private void notify(DailySpendSummaryEvent event) {
        List<String> recipients = includeKeyContact
                ? alertRecipients.forKey(event.tenantId(), event.apiKeyId())
                : alertRecipients.ownersAndAdmins(event.tenantId());
        if (recipients.isEmpty()) {
            log.warn("No one to email the daily summary for key {} in tenant {}", event.apiKeyId(), event.tenantId());
            return;
        }

        String keyName = alertRecipients.keyName(event.tenantId(), event.apiKeyId()).orElse("your API key");
        String cost = event.currency() + " " + money(event.cost());
        String rate = event.currency() + " " + event.pricePerDelivered().stripTrailingZeros().toPlainString();

        String subject = "WhatsApp notifications on " + event.periodStart() + ": "
                + event.delivered() + " delivered, " + cost;

        String text = "Daily summary for " + keyName + " on " + event.periodStart() + " (UTC)\n\n"
                + "Messages sent: " + event.sends() + "\n"
                + "Delivered (charged): " + event.delivered() + "\n"
                + "Failed (not charged): " + event.failed() + "\n"
                + "Awaiting delivery confirmation: " + event.awaitingConfirmation() + "\n\n"
                + "Estimated cost: " + cost + " (" + event.delivered() + " delivered x " + rate + ")\n\n"
                + footer(event, rate);

        String html = "<p>Daily summary for <strong>" + HtmlUtils.htmlEscape(keyName) + "</strong> on "
                + event.periodStart() + " (UTC)</p>"
                + "<table cellpadding=\"4\">"
                + row("Messages sent", String.valueOf(event.sends()))
                + row("Delivered (charged)", String.valueOf(event.delivered()))
                + row("Failed (not charged)", String.valueOf(event.failed()))
                + row("Awaiting delivery confirmation", String.valueOf(event.awaitingConfirmation()))
                + row("<strong>Estimated cost</strong>", "<strong>" + cost + "</strong>")
                + "</table>"
                + "<p>" + event.delivered() + " delivered x " + rate + "</p>"
                + "<p>" + footer(event, rate).replace("\n\n", "</p><p>") + "</p>";

        for (String recipient : recipients) {
            emailGateway.send(recipient, subject, html, text);
        }
    }

    /**
     * States what the figure is and is not. It is an estimate at a rate
     * we configure, not Meta's invoice; and a message still awaiting a
     * receipt may be charged when the receipt arrives, which is the one way
     * this number can end up lower than the bill.
     */
    private static String footer(DailySpendSummaryEvent event, String rate) {
        String footer = "Only delivered messages are charged, at " + rate + " each. This is an estimate "
                + "based on that rate, not Meta's invoice.";
        if (event.awaitingConfirmation() > 0) {
            footer += "\n\nMessages still awaiting confirmation are not included. If they are delivered "
                    + "later they will be charged too.";
        }
        return footer;
    }

    private static String row(String label, String value) {
        return "<tr><td>" + label + "</td><td align=\"right\">" + value + "</td></tr>";
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
