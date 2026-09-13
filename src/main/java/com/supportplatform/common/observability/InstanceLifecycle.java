package com.supportplatform.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * When this instance became able to serve traffic, and how long ago that
 * was.
 *
 * <p>Exists because of how this service is deployed: Render's free tier
 * spins the instance down after ~15 minutes idle, and the next request
 * pays a measured ~128 second cold start. That is long enough for a
 * caller's HTTP client to time out and retry, which is the leading theory
 * for the duplicate sends — but nothing in the logs said "this instance
 * just woke up", so a burst of sends at 03:14 was indistinguishable from a
 * burst of genuine traffic at 03:14.
 *
 * <p>The readiness line is deliberately loud and greppable
 * ("INSTANCE READY"). Correlating it with send volume is the whole point:
 * sends clustered in the first seconds after one of these lines are queued
 * retries landing on a freshly woken server, not new business.
 */
@Component
public class InstanceLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InstanceLifecycle.class);

    /**
     * How soon after readiness a request is still considered part of the
     * wake-up burst. Generous on purpose: the cold start itself is ~128s, so
     * clients that timed out and backed off are still arriving well after
     * the instance is technically ready.
     */
    public static final Duration COLD_WINDOW = Duration.ofMinutes(3);

    private volatile Instant readyAt;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        this.readyAt = Instant.now();
        log.warn("INSTANCE READY at {} — this process just started. Requests served in the next {}s "
                        + "are flagged coldStart=true; a burst of them is queued client retries, not new traffic.",
                readyAt, COLD_WINDOW.toSeconds());
    }

    /** Null before readiness — treated as "not cold" rather than guessed at. */
    public Instant readyAt() {
        return readyAt;
    }

    /** True while this instance is still inside its post-wake window. */
    public boolean withinColdWindow(Instant now) {
        Instant ready = this.readyAt;
        return ready != null && Duration.between(ready, now).compareTo(COLD_WINDOW) < 0;
    }

    /** Seconds since this instance became ready, or -1 if it is not ready yet. */
    public long secondsSinceReady(Instant now) {
        Instant ready = this.readyAt;
        return ready == null ? -1L : Duration.between(ready, now).toSeconds();
    }
}
