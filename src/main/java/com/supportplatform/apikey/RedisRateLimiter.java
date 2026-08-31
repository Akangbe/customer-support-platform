package com.supportplatform.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared rate-limit counters in Redis — the only class in the codebase
 * permitted to import a Redis shape (Rule 4's pattern applied to the
 * throttling boundary). Redis is an approved *ephemeral* store for exactly
 * this per {@code system-architecture.md}; Rule 2 still holds, since
 * nothing authoritative lives here — losing every counter costs at most
 * one window of over-permissiveness.
 *
 * <p>Two keys per caller per window ({@code INCR} + {@code EXPIRE} on the
 * current bucket, {@code GET} on the previous), evaluated by
 * {@link SlidingWindow}. Not active by default — see
 * {@link InMemoryRateLimiter} for why.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "redis-enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:";
    private static final Duration BUCKET_TTL = Duration.ofMillis(SlidingWindow.WINDOW_MILLIS * 2);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long bucket = now / SlidingWindow.WINDOW_MILLIS;

        try {
            String currentKey = KEY_PREFIX + key + ":" + bucket;
            Long currentCount = redis.opsForValue().increment(currentKey);
            redis.expire(currentKey, BUCKET_TTL);

            String previousRaw = redis.opsForValue().get(KEY_PREFIX + key + ":" + (bucket - 1));
            long previousCount = previousRaw == null ? 0L : Long.parseLong(previousRaw);

            return SlidingWindow.isWithinLimit(currentCount == null ? 1L : currentCount, previousCount, now, limit);
        } catch (DataAccessException | NumberFormatException e) {
            // Fail open: Redis is an ephemeral convenience, and a throttling
            // outage must not take the whole send API down with it. The key
            // itself is still authenticated against Postgres, which is what
            // actually protects tenant data (Rule 2, Rule 3).
            log.warn("Rate limit check failed for {}; allowing the request: {}", key, e.getMessage());
            return true;
        }
    }
}
