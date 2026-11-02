package com.supportplatform.client.dto;

import com.supportplatform.notification.NotificationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A POST body rather than query parameters because {@code recipient} is a
 * phone number, and a URL ends up in access logs. All fields are optional.
 *
 * @param recipient a full number, matched exactly
 * @param size      page size, 50 by default
 */
public record ClientMessageSearchRequest(
        LocalDate from,
        LocalDate to,
        NotificationStatus status,
        @Size(max = 255) String template,
        @Pattern(regexp = "\\+?[0-9]{5,20}") String recipient,
        @Size(max = 200) String cursor,
        @Min(1) @Max(100) Integer size
) {
}
