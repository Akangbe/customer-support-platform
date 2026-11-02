package com.supportplatform.auth.passwordreset;

import java.time.Duration;

/**
 * Carries the raw token, unlike other events, because only its hash is stored
 * and the email listener could not get it back. Never log this event.
 */
public record PasswordResetRequestedEvent(String email, String token, Duration validFor) {
}
