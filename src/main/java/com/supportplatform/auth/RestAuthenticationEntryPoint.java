package com.supportplatform.auth;

import tools.jackson.databind.ObjectMapper;
import com.supportplatform.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handles requests to protected endpoints with no session at all. This runs
 * inside Spring Security's filter chain, before the request ever reaches
 * DispatcherServlet, so {@link com.supportplatform.common.error.GlobalExceptionHandler}
 * never sees it — the response has to be written directly here, using the
 * same {@link ErrorResponse} shape for consistency.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Authentication is required", request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
