package com.supportplatform.email;

import com.supportplatform.auth.passwordreset.PasswordChangedEvent;
import com.supportplatform.auth.passwordreset.PasswordResetRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** After commit, and a failed send is only logged, the same as {@link InviteEmailListener}. */
@Component
public class PasswordResetEmailListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailListener.class);

    private final EmailGateway emailGateway;
    private final String resetUrlTemplate;

    public PasswordResetEmailListener(EmailGateway emailGateway,
                                        @Value("${app.email.password-reset-url-template}") String resetUrlTemplate) {
        this.emailGateway = emailGateway;
        this.resetUrlTemplate = resetUrlTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResetRequested(PasswordResetRequestedEvent event) {
        String link = String.format(resetUrlTemplate, event.token());
        long minutes = event.validFor().toMinutes();
        send(event.email(), "Reset your password",
                "<p>We received a request to reset your password.</p>"
                        + "<p><a href=\"" + link + "\">Choose a new password</a></p>"
                        + "<p>This link works once and expires in " + minutes + " minutes. "
                        + "If you didn't ask for this, you can ignore this email. Your password stays the same.</p>",
                "We received a request to reset your password.\n\nChoose a new password: " + link
                        + "\n\nThis link works once and expires in " + minutes + " minutes. "
                        + "If you didn't ask for this, you can ignore this email. Your password stays the same.");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedEvent event) {
        String message = "Your password was just changed, and any devices that were signed in have been signed out.";
        String warning = "If this wasn't you, contact Relaydesk straight away.";
        send(event.email(), "Your password was changed",
                "<p>" + message + "</p><p>" + warning + "</p>",
                message + "\n\n" + warning);
    }

    /** Logs the failure type only: the message could echo the address or the link. */
    private void send(String to, String subject, String html, String text) {
        try {
            emailGateway.send(to, subject, html, text);
        } catch (Exception e) {
            log.warn("Failed to send \"{}\" email: {}", subject, e.getClass().getSimpleName());
        }
    }
}
