package com.supportplatform.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login brute-force guard, keyed by email. No Redis or other
 * shared store: this is a single Spring Boot instance (system-architecture.md
 * §7 — the same reasoning ADR-018 already applied to the realtime broker),
 * so process-local state reaches every login request there is. Revisit if
 * the app ever runs more than one instance.
 *
 * <p>Failures within {@link #ATTEMPT_WINDOW} of each other count toward the
 * same streak; a gap longer than that resets the count, so a handful of
 * mistyped passwords spread out over time never permanently locks anyone
 * out. Hitting {@link #MAX_ATTEMPTS} within the window locks that email out
 * for {@link #LOCKOUT_DURATION}. Tracked by email rather than caller IP
 * (deliberately — an attacker distributing guesses across many source IPs
 * against one account is the case this exists to stop; IP-based throttling
 * is a different, additive control with no current requirement behind it).
 */
@Component
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private record Attempt(int failureCount, Instant lastFailureAt, Instant lockedUntil) {
    }

    /** Called before authenticating; throws if this email is currently locked out. */
    public void checkNotLocked(String email) {
        Attempt attempt = attempts.get(key(email));
        if (attempt == null || attempt.lockedUntil() == null) {
            return;
        }
        Instant now = Instant.now();
        if (attempt.lockedUntil().isAfter(now)) {
            throw new TooManyLoginAttemptsException(Duration.between(now, attempt.lockedUntil()).toSeconds() + 1);
        }
    }

    public void recordFailure(String email) {
        attempts.compute(key(email), (k, existing) -> {
            Instant now = Instant.now();
            boolean withinWindow = existing != null
                    && Duration.between(existing.lastFailureAt(), now).compareTo(ATTEMPT_WINDOW) <= 0;
            int newCount = (withinWindow ? existing.failureCount() : 0) + 1;
            Instant lockedUntil = newCount >= MAX_ATTEMPTS ? now.plus(LOCKOUT_DURATION) : null;
            return new Attempt(newCount, now, lockedUntil);
        });
    }

    public void recordSuccess(String email) {
        attempts.remove(key(email));
    }

    /** Sweeps stale entries so attempts against made-up emails can't grow this map without bound. */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            Instant staleAt = a.lockedUntil() != null ? a.lockedUntil() : a.lastFailureAt().plus(ATTEMPT_WINDOW);
            return staleAt.isBefore(now);
        });
    }

    private String key(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
