package com.supportplatform.notification;

import com.supportplatform.auth.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Carries the already-authenticated session identity into the WebSocket
 * session's attributes (realtime-domain.md §3) — the same session cookie
 * that authenticates every REST call (ADR-014), read the same way
 * {@code AbstractIntegrationTest.extractTenantId} does in tests. Spring
 * Security's normal {@code anyRequest().authenticated()} rule already
 * rejects an unauthenticated handshake before this ever runs; this is
 * what makes the identity available for the life of the socket
 * afterward, for {@link TenantSubscriptionInterceptor} to check.
 */
@Component
public class PrincipalHandshakeInterceptor implements HandshakeInterceptor {

    public static final String TENANT_ID_ATTRIBUTE = "tenantId";
    public static final String USER_ID_ATTRIBUTE = "userId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpSession session = servletRequest.getServletRequest().getSession(false);
        if (session == null) {
            return false;
        }
        SecurityContext context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null
                || !(context.getAuthentication().getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return false;
        }

        attributes.put(TENANT_ID_ATTRIBUTE, principal.getTenantId());
        attributes.put(USER_ID_ATTRIBUTE, principal.getUserId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
