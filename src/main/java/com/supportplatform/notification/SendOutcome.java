package com.supportplatform.notification;

/**
 * A send attempt, plus whether it actually reached Meta.
 *
 * <p>{@code replay} cannot be read off the log row: a suppressed retry
 * returns the <em>original</em> row, which looks exactly like a fresh
 * success. The caller needs the difference — that is what
 * {@code X-Idempotent-Replay} tells it — and so does anyone reading the
 * logs afterwards.
 */
public record SendOutcome(NotificationLog log, boolean replay) {

    static SendOutcome sent(NotificationLog log) {
        return new SendOutcome(log, false);
    }

    static SendOutcome replayed(NotificationLog original) {
        return new SendOutcome(original, true);
    }
}
