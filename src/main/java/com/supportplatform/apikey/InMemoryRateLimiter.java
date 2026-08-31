package com.supportplatform.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local rate limiting, for the single-instance deployment ADR-018
 * already assumes and {@code LoginAttemptService} already relies on. This
 * is the default: local, dev and the test suite all run on it, so none of
 * them need Redis running just to boot. Flip
 * {@code app.rate-limit.redis-enabled=true} to swap in
 * {@link RedisRateLimiter}, which is what a multi-instance deployment
 * needs — process-local counters would then let a caller spend its budget
 * once per instance.
 *
 * <p>Uses the same two-bucket weighted sliding window as the Redis
 * implementation, so switching between them doesn't change the limit's
 * observable behaviour.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimiter.class);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * The multi-instance trap, made loud. Nothing in the process can detect
     * how many instances are running, so this cannot be a hard failure —
     * but a per-instance limiter silently multiplying every tenant's budget
     * by the instance count is exactly the kind of thing that is only
     * noticed after a bill, so it says so at every boot rather than only in
     * a config comment.
     */
    InMemoryRateLimiter() {
        log.warn("Rate limiting is PROCESS-LOCAL (app.rate-limit.redis-enabled=false). Correct for a single "
                + "instance only — running more than one instance grants every API key its full per-minute "
                + "budget on each one. Set app.rate-limit.redis-enabled=true with spring.data.redis.* before scaling out.");
    }

    private record Window(long bucket, long currentCount, long previousCount) {
    }

    @Override
    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long bucket = now / SlidingWindow.WINDOW_MILLIS;

        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.bucket() < bucket - 1) {
                return new Window(bucket, 1, 0);
            }
            if (existing.bucket() == bucket - 1) {
                return new Window(bucket, 1, existing.currentCount());
            }
            return new Window(bucket, existing.currentCount() + 1, existing.previousCount());
        });

        return SlidingWindow.isWithinLimit(window.currentCount(), window.previousCount(), now, limit);
    }

    /** Sweeps windows nobody has touched for two full periods, so one-off keys can't grow this map without bound. */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpired() {
        long staleBefore = (System.currentTimeMillis() / SlidingWindow.WINDOW_MILLIS) - 1;
        windows.entrySet().removeIf(e -> e.getValue().bucket() < staleBefore);
    }
}
