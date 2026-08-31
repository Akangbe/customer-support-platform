package com.supportplatform.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Deliberately has no {@code tenantId} field, and never will: the tenant
 * is resolved from the API key (Rule 3). A caller that could name its own
 * tenant here could send on any tenant's number.
 *
 * <p>The E.164 pattern is the same one {@code CreateCustomerRequest}
 * already enforces on customer phone numbers.
 */
public record SendNotificationRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "must be in E.164 format, e.g. +14155552671")
        String recipient,

        @NotBlank @Size(max = 255) String templateName,

        @Size(max = 20) String languageCode,

        List<@Size(max = 1024) String> bodyParams,

        @Size(max = 1024) String buttonUrlParam
) {
    public SendNotificationRequest {
        bodyParams = bodyParams == null ? List.of() : List.copyOf(bodyParams);
        languageCode = languageCode == null || languageCode.isBlank() ? "en" : languageCode;
    }
}
