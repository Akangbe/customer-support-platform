package com.supportplatform.common.error;

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

    public record FieldError(String field, String message) {
    }
}
