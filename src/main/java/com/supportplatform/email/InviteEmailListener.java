package com.supportplatform.email;

import com.supportplatform.user.User;
import com.supportplatform.user.UserInvitedEvent;
import com.supportplatform.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Turns a {@link UserInvitedEvent} into an email — the email-domain
 * counterpart of {@code RealtimeEventListener}'s WebSocket broadcasts.
 * Fires {@code AFTER_COMMIT} only and swallows send failures to a warning:
 * a delivery problem must never turn an already-committed invite into a
 * failed HTTP response for the inviter. The invite token returned in
 * {@code InviteUserResponse} remains the fallback if email delivery fails
 * or SES isn't configured (identity-and-access.md §1, §5).
 */
@Component
public class InviteEmailListener {

    private static final Logger log = LoggerFactory.getLogger(InviteEmailListener.class);
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneOffset.UTC);

    private final EmailGateway emailGateway;
    private final UserService userService;
    private final String acceptInviteUrlTemplate;

    public InviteEmailListener(EmailGateway emailGateway, UserService userService,
                                @Value("${app.email.accept-invite-url-template}") String acceptInviteUrlTemplate) {
        this.emailGateway = emailGateway;
        this.userService = userService;
        this.acceptInviteUrlTemplate = acceptInviteUrlTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserInvited(UserInvitedEvent event) {
        try {
            User user = userService.getWithinTenant(event.tenantId(), event.userId());
            String link = String.format(acceptInviteUrlTemplate, user.getInviteToken());
            String expiry = EXPIRY_FORMAT.format(user.getInviteTokenExpiresAt());

            String subject = "You've been invited to join a workspace";
            String text = "You've been invited as " + user.getRole() + ". Accept your invite: " + link
                    + "\n\nThis link expires on " + expiry + ".";
            String html = "<p>You've been invited to join a workspace as <strong>" + user.getRole() + "</strong>.</p>"
                    + "<p><a href=\"" + link + "\">Accept your invite</a></p>"
                    + "<p>This link expires on " + expiry + ".</p>";

            emailGateway.send(user.getEmail(), subject, html, text);
        } catch (Exception e) {
            log.warn("Failed to send invite email for user {}: {}", event.userId(), e.getMessage());
        }
    }
}
