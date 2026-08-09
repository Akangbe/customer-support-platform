package com.supportplatform.common.error;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * Stable API error shape. Never carries stack traces or internal exception
 * messages — {@link GlobalExceptionHandler} is responsible for translating
 * internal failures into safe, client-facing detail.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    /**
     * Used both by {@link GlobalExceptionHandler} and by security components
     * (authentication entry point, access denied handler) that sit outside
     * normal Spring MVC exception resolution, so every error response shares
     * this exact shape regardless of which layer produced it.
     */
    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, path, List.of());
    }

    public record FieldError(String field, String message) {
    }
}
