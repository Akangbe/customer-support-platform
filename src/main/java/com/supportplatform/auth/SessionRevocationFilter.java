package com.supportplatform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends a session whose user was revoked after it signed in. The request then
 * continues as anonymous, so the normal rules answer it. Created by
 * {@link SecurityConfig} and deliberately not a bean, since Spring Boot would
 * also register a bean as a filter for every request.
 */
class SessionRevocationFilter extends OncePerRequestFilter {

    private final SessionRevocations revocations;

    SessionRevocationFilter(SessionRevocations revocations) {
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                && revocations.isRevoked(principal.getUserId(), principal.getAuthenticatedAt())) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
