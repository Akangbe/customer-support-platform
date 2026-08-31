package com.supportplatform.apikey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * A second filter chain, scoped to the machine-to-machine notification API
 * and ordered ahead of {@code SecurityConfig}'s (which stays unordered,
 * i.e. last, and continues to match everything else unchanged).
 *
 * <p>Kept separate rather than folded into the existing chain because the
 * two have opposite session semantics: ADR-014's browser chain is
 * cookie-and-session based, while this one is strictly stateless — a
 * tenant's backend presents its key on every request and must never be
 * handed a {@code JSESSIONID} it would then have to manage. CORS is off
 * for the same reason: these calls come from a server, not a browser.
 */
@Configuration
public class ApiKeySecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    /**
     * The filter is constructed here rather than being a {@code @Component}
     * on purpose: Spring Boot auto-registers any {@code Filter} bean against
     * <em>every</em> request, which would put API-key authentication in front
     * of the session-authenticated dashboard endpoints too. Keeping it out of
     * the bean container means it runs only where this chain places it.
     */
    public ApiKeySecurityConfig(ApiKeyService apiKeyService, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.apiKeyAuthenticationFilter = new ApiKeyAuthenticationFilter(apiKeyService, rateLimiter, objectMapper);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/notifications/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Belt and braces with STATELESS: nothing may write a security
                // context to a session on this chain.
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());

        return http.build();
    }
}
