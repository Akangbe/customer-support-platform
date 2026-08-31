package com.supportplatform.apikey;

/**
 * The boundary interface for per-key request throttling — the same
 * interface/implementation split the codebase already uses for WhatsApp,
 * storage and email. {@link RedisRateLimiter} is the only class permitted
 * to import a Redis shape; {@link InMemoryRateLimiter} is the
 * single-instance fallback (ADR-018's posture), and is what local, dev and
 * tests run on so none of them need a Redis box to boot.
 */
public interface RateLimiter {

    /**
     * Records one request against {@code key} and reports whether it is
     * within {@code limit} requests per minute.
     *
     * @return {@code true} if the request is allowed, {@code false} if it exceeds the limit
     */
    boolean tryAcquire(String key, int limit);
}
