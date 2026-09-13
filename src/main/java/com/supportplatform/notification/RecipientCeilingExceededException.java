package com.supportplatform.notification;

/**
 * Too many notifications have already gone to this recipient for this
 * template inside the configured window.
 *
 * <p>A 429 rather than a silent drop, and that choice is the whole design.
 * The alternative — quietly not sending and reporting success — is what a
 * naive deduplication does, and it is strictly worse: the caller believes
 * the customer was told something they were never told. Answering with a
 * status and a {@code Retry-After} keeps the caller informed and able to
 * act, exactly as {@code TooManyLoginAttemptsException} already does.
 *
 * <p>Distinct from the per-key rate limit in {@code ApiKeyAuthenticationFilter},
 * which counts requests regardless of content and so cannot tell one
 * notification from the same notification sent forty times.
 */
public class RecipientCeilingExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RecipientCeilingExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
