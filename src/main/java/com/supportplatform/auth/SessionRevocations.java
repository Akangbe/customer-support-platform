package com.supportplatform.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which users have had their existing sessions ended, and when. Held in memory
 * because the sessions are too: a restart loses both together, so nothing can
 * outlive its revocation. Like {@link LoginAttemptService}, this assumes one
 * application instance.
 */
@Component
public class SessionRevocations {

    private final Map<UUID, Instant> revokedAt = new ConcurrentHashMap<>();

    public void revokeExisting(UUID userId) {
        revokedAt.put(userId, Instant.now());
    }

    boolean isRevoked(UUID userId, Instant authenticatedAt) {
        Instant cutoff = revokedAt.get(userId);
        return cutoff != null && !authenticatedAt.isAfter(cutoff);
    }
}
