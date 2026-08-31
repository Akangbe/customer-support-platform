package com.supportplatform.apikey;

/**
 * The window arithmetic shared by both {@link RateLimiter} implementations,
 * so the Redis and in-memory paths cannot drift into enforcing subtly
 * different limits.
 *
 * <p>A plain fixed window lets a caller send {@code 2 * limit} requests
 * across a bucket boundary (all of them in the last second of one minute
 * and the first second of the next). This is the standard weighted
 * approximation instead: the previous bucket's count still counts, faded
 * out in proportion to how far into the current bucket we are. It needs
 * only {@code INCR}/{@code EXPIRE}/{@code GET} — no sorted sets, no
 * per-request timestamp storage.
 */
final class SlidingWindow {

    static final long WINDOW_MILLIS = 60_000L;

    private SlidingWindow() {
    }

    static boolean isWithinLimit(long currentCount, long previousCount, long nowMillis, int limit) {
        double elapsedFraction = (nowMillis % WINDOW_MILLIS) / (double) WINDOW_MILLIS;
        double weighted = previousCount * (1.0 - elapsedFraction) + currentCount;
        return weighted <= limit;
    }

    /** Whole seconds until the current bucket rolls over — what a 429 tells the caller to wait. */
    static long secondsUntilWindowReset(long nowMillis) {
        return ((WINDOW_MILLIS - (nowMillis % WINDOW_MILLIS)) / 1000) + 1;
    }
}
