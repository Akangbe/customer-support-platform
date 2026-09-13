package com.supportplatform.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Stamps every request with an id and puts it in the MDC, so each log line
 * written while handling that request carries it and one request's whole
 * lifecycle can be pulled out of an interleaved log.
 *
 * <p>Unlike {@code ApiKeyAuthenticationFilter} — which is deliberately kept
 * out of the bean container so it runs only on its own chain — this one IS
 * a {@code @Component}, precisely so Spring Boot auto-registers it against
 * every request. Tracing that covers only some endpoints is tracing you
 * cannot trust when you most need it.
 *
 * <p>Runs at highest precedence so the id exists before authentication,
 * rate limiting or anything else can log.
 *
 * <h2>MDC keys</h2>
 * <ul>
 *   <li>{@code requestId} — this request</li>
 *   <li>{@code coldStart} — whether this instance had only just woken</li>
 *   <li>{@code sinceReadyS} — seconds since readiness, for ordering a burst</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_COLD_START = "coldStart";
    public static final String MDC_SINCE_READY = "sinceReadyS";

    /**
     * Resolved lazily rather than injected directly because {@code Filter}
     * beans are registered in {@code @WebMvcTest} slices while ordinary
     * {@code @Component}s are not — a hard dependency here fails those
     * slices at context load. A request id is useful on its own, so the
     * cold-start annotation degrades to "unknown" rather than taking the
     * whole filter down with it.
     */
    private final ObjectProvider<InstanceLifecycle> lifecycle;

    public RequestIdFilter(ObjectProvider<InstanceLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        Instant now = Instant.now();

        InstanceLifecycle instance = lifecycle.getIfAvailable();
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_COLD_START, instance == null ? "unknown" : Boolean.toString(instance.withinColdWindow(now)));
        MDC.put(MDC_SINCE_READY, instance == null ? "-1" : Long.toString(instance.secondsSinceReady(now)));
        // Echoed back so a caller can quote it in a support request, and so
        // a caller that retries can be asked which id it saw first.
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat pools threads; a leaked MDC entry would attach this
            // request's id to an unrelated one later.
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_COLD_START);
            MDC.remove(MDC_SINCE_READY);
        }
    }

    /**
     * A caller-supplied id is honoured so a trace can span their system and
     * ours — which is exactly what is needed to prove "these three requests
     * were one intent retried", since only the caller knows that. It is
     * length-capped and stripped of anything outside a conservative
     * character set: this value reaches log files and a response header, so
     * it must not be able to carry newlines (log forging) or header
     * delimiters.
     */
    private static String resolve(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = supplied.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
