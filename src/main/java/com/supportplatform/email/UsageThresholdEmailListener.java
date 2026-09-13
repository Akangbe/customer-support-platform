package com.supportplatform.email;

import com.supportplatform.apikey.ApiKey;
import com.supportplatform.apikey.ApiKeyRepository;
import com.supportplatform.notification.DailyUsageThresholdEvent;
import com.supportplatform.notification.NotificationUsageAlert;
import com.supportplatform.notification.NotificationUsageAlertRepository;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Emails a tenant's Owners and Admins — and the integrator behind the key,
 * when one is on file — the first time a day's send volume crosses a
 * threshold.
 *
 * <p>Follows {@link DeliveryBlockedEmailListener}: the log line is written
 * first and unconditionally, so a log-based alarm still works when SES is
 * unconfigured, and email failures are swallowed to a warning so a dead
 * mailbox cannot fail the send that noticed the problem.
 *
 * <p>It departs from that listener in one respect, deliberately. Its
 * cooldown lives in a {@code ConcurrentHashMap}, which is right for a guard
 * measured in minutes. This guard spans a day on a service that cold starts
 * several times a day, so it is a row in {@code notification_usage_alert}
 * instead: the unique constraint is what makes the alert happen once, and
 * it survives a restart.
 */
@Component
public class UsageThresholdEmailListener {

    private static final Logger log = LoggerFactory.getLogger(UsageThresholdEmailListener.class);

    private static final Set<UserRole> ALERTABLE_ROLES = Set.of(UserRole.OWNER, UserRole.ADMIN);

    private final EmailGateway emailGateway;
    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final NotificationUsageAlertRepository alertRepository;

    public UsageThresholdEmailListener(EmailGateway emailGateway, UserRepository userRepository,
                                         ApiKeyRepository apiKeyRepository,
                                         NotificationUsageAlertRepository alertRepository) {
        this.emailGateway = emailGateway;
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.alertRepository = alertRepository;
    }

    /**
     * {@code fallbackExecution} because the send path is deliberately not
     * {@code @Transactional} — there is no commit to hang off, and without
     * it this listener would silently never run. The phase still matters for
     * any caller that does publish inside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onThresholdCrossed(DailyUsageThresholdEvent event) {
        if (!claim(event)) {
            return;
        }

        // Written first and unconditionally: this is what a log-based alarm
        // watches, and it survives SES being unconfigured.
        log.warn("NOTIFICATION VOLUME THRESHOLD: tenant {} has sent {} notifications on {} (UTC), crossing {}.",
                event.tenantId(), event.sendsToday(), event.periodStart(), event.threshold());

        try {
            notify(event);
        } catch (Exception e) {
            log.warn("Could not email the usage-threshold alert for tenant {}: {}", event.tenantId(), e.getMessage());
        }
    }

    /**
     * @return true if this caller is the one that gets to send the alert.
     *         The insert is the claim: two instances, or two threads, racing
     *         on the same threshold both reach here and the unique
     *         constraint settles it.
     */
    private boolean claim(DailyUsageThresholdEvent event) {
        try {
            alertRepository.save(new NotificationUsageAlert(event.tenantId(), event.periodStart(),
                    event.threshold(), (int) Math.min(event.sendsToday(), Integer.MAX_VALUE)));
            return true;
        } catch (DataIntegrityViolationException alreadyAlerted) {
            return false;
        }
    }

    private void notify(DailyUsageThresholdEvent event) {
        List<String> recipients = addressees(event);
        if (recipients.isEmpty()) {
            log.warn("No one to email about the volume threshold crossed by tenant {}", event.tenantId());
            return;
        }

        String subject = "WhatsApp notifications: " + event.sendsToday() + " sent today";
        String text = "This account has sent " + event.sendsToday() + " WhatsApp notifications on "
                + event.periodStart() + " (UTC), passing the alert threshold of " + event.threshold() + ".\n\n"
                + "If that is expected, nothing needs doing. If it is not, the usual cause is a caller "
                + "re-sending the same notification, and the fastest check is whether the number of messages "
                + "is far above the number of people who received them.\n\n"
                + "An API key can be switched off immediately from the dashboard without deleting it.";
        String html = "<p>This account has sent <strong>" + event.sendsToday() + "</strong> WhatsApp notifications on "
                + event.periodStart() + " (UTC), passing the alert threshold of <strong>" + event.threshold()
                + "</strong>.</p>"
                + "<p>If that is expected, nothing needs doing. If it is not, the usual cause is a caller "
                + "re-sending the same notification, and the fastest check is whether the number of messages "
                + "is far above the number of people who received them.</p>"
                + "<p>An API key can be switched off immediately from the dashboard without deleting it.</p>";

        for (String recipient : recipients) {
            emailGateway.send(recipient, subject, html, text);
        }
    }

    /**
     * The tenant's own Owners and Admins, plus the key's contact if one was
     * recorded. A {@link LinkedHashSet} because an integrator who is also a
     * user of the tenant should get one mail, not two, and because the
     * tenant's own people should be first in the list.
     */
    private List<String> addressees(DailyUsageThresholdEvent event) {
        Set<String> unique = new LinkedHashSet<>();

        for (User user : userRepository.findAllByTenantIdAndRoleInAndStatus(
                event.tenantId(), ALERTABLE_ROLES, UserStatus.ACTIVE)) {
            unique.add(user.getEmail());
        }

        contactEmail(event.apiKeyId(), event.tenantId()).ifPresent(unique::add);

        return new ArrayList<>(unique);
    }

    private java.util.Optional<String> contactEmail(UUID apiKeyId, UUID tenantId) {
        if (apiKeyId == null) {
            return java.util.Optional.empty();
        }
        return apiKeyRepository.findById(apiKeyId)
                // Tenant-scoped even on a lookup by primary key (Rule 3): an
                // id that belongs to another tenant must not resolve here.
                .filter(key -> key.getTenantId().equals(tenantId))
                .map(ApiKey::getContactEmail)
                .filter(email -> email != null && !email.isBlank());
    }
}
