package com.supportplatform.apikey;

import com.supportplatform.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates machine callers on {@code /api/v1/notifications/**} from an
 * {@code rd_live_...} key, and throttles them per key.
 *
 * <p>The tenant this request acts as is taken off the key row and put in
 * the security context here (Rule 3) — downstream code reads it from
 * {@link ApiKeyPrincipal} and must never read a tenant id out of the
 * request body.
 *
 * <p>Runs inside Spring Security's chain, before DispatcherServlet, so
 * {@code GlobalExceptionHandler} cannot see failures from it; 401 and 429
 * are written directly here in the same {@link ErrorResponse} shape as
 * everywhere else, exactly as {@code RestAuthenticationEntryPoint} does.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presentedKey = extractKey(request);
        if (presentedKey == null) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    "An API key is required. Send it as 'Authorization: Bearer rd_live_...' or the X-API-Key header.");
            return;
        }

        Optional<ApiKeyPrincipal> principal = apiKeyService.authenticate(presentedKey);
        if (principal.isEmpty()) {
            // Deliberately one message for every failure mode (malformed,
            // unknown, revoked, wrong secret) so nothing is enumerable.
            writeError(request, response, HttpStatus.UNAUTHORIZED, "Invalid or revoked API key");
            return;
        }

        ApiKeyPrincipal authenticated = principal.get();
        if (!rateLimiter.tryAcquire(authenticated.keyId(), authenticated.rateLimit())) {
            long retryAfter = SlidingWindow.secondsUntilWindowReset(System.currentTimeMillis());
            log.info("Rate limit exceeded for api key {} (tenant {})", authenticated.keyId(), authenticated.tenantId());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            writeError(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Retry in " + retryAfter + " seconds.");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(authenticated, null,
                List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))));
        SecurityContextHolder.setContext(context);

        apiKeyService.touch(authenticated.apiKeyId());

        try {
            chain.doFilter(request, response);
        } finally {
            // Stateless chain: nothing persists this context, so clearing it
            // keeps a pooled request thread from carrying a tenant identity
            // into the next request it serves (Rule 3).
            SecurityContextHolder.clearContext();
        }
    }

    /** {@code Authorization: Bearer <key>} is the primary form; {@code X-API-Key} is accepted for callers whose HTTP client reserves Authorization. */
    private String extractKey(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String value = authorization.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        String headerKey = request.getHeader(API_KEY_HEADER);
        return headerKey == null || headerKey.isBlank() ? null : headerKey.trim();
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, message, request.getRequestURI()));
    }
}
