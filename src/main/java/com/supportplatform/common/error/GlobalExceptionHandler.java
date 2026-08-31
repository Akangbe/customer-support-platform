package com.supportplatform.common.error;

import com.supportplatform.auth.TooManyLoginAttemptsException;
import com.supportplatform.conversation.InvalidConversationStateException;
import com.supportplatform.customer.CustomerAlreadyExistsException;
import com.supportplatform.message.OutsideServiceWindowException;
import com.supportplatform.notification.NotificationDeliveryException;
import com.supportplatform.notification.TemplateNotAllowedException;
import com.supportplatform.user.EmailAlreadyRegisteredException;
import com.supportplatform.user.InvalidInviteTokenException;
import com.supportplatform.user.LastOwnerException;
import com.supportplatform.whatsapp.WhatsAppCodeExchangeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Single translation point from exceptions to the API's stable error shape.
 * Client-facing messages never include exception messages or stack traces
 * for anything that isn't already a deliberate, safe validation message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> new ErrorResponse.FieldError(cv.getPropertyPath().toString(), cv.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request, List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return build(status, message, request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", request, List.of());
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), ex.getMessage(), request.getRequestURI(), List.of());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request, List.of());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(LastOwnerException.class)
    public ResponseEntity<ErrorResponse> handleLastOwner(LastOwnerException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidInviteTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInviteToken(InvalidInviteTokenException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(CustomerAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleCustomerAlreadyExists(CustomerAlreadyExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidConversationStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidConversationState(InvalidConversationStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(OutsideServiceWindowException.class)
    public ResponseEntity<ErrorResponse> handleOutsideServiceWindow(OutsideServiceWindowException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(WhatsAppCodeExchangeException.class)
    public ResponseEntity<ErrorResponse> handleWhatsAppCodeExchange(WhatsAppCodeExchangeException ex, HttpServletRequest request) {
        log.warn("WhatsApp Embedded Signup code exchange failed: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST,
                "WhatsApp Embedded Signup could not be completed — the authorization code may be invalid or expired.",
                request, List.of());
    }

    /**
     * The caller asked for a template their tenant doesn't have, or has but
     * isn't approved. 422 rather than 400: the request is well-formed, it's
     * the tenant's template configuration that doesn't permit it. The
     * message is safe to pass through — it describes the caller's own
     * allowlist and names nothing internal.
     */
    @ExceptionHandler(TemplateNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleTemplateNotAllowed(TemplateNotAllowedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of());
    }

    /**
     * A send we accepted, authenticated and rate-limited, that Meta then
     * refused: 502, because the failure is downstream of us, not in the
     * caller's request. The reason is already in {@code notification_log}
     * and in our logs — the caller gets the id, not Meta's error text.
     */

    @ExceptionHandler(NotificationDeliveryException.class)
    public ResponseEntity<ErrorResponse> handleNotificationDelivery(NotificationDeliveryException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY,
                "WhatsApp rejected this notification. Check the template name, its approval status and the recipient, "
                        + "then retry. Reference: " + ex.getNotificationId(),
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request,
                                                 List<ErrorResponse.FieldError> fieldErrors) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
