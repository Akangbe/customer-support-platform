package com.supportplatform.notification.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deliberately has no {@code tenantId} field, and never will: the tenant
 * is resolved from the API key (Rule 3). A caller that could name its own
 * tenant here could send on any tenant's number.
 *
 * <p>The E.164 pattern is the same one {@code CreateCustomerRequest}
 * already enforces on customer phone numbers.
 *
 * <p>{@code bodyParams} fills a template written with positional
 * placeholders ({@code {{1}}}); {@code namedBodyParams} fills one written
 * with named placeholders ({@code {{variable}}}). Meta rejects the wrong
 * shape for a template, so a request carries one or the other, never both.
 * The value rules on named params are Meta's own limits on a text
 * parameter, checked here so a bad value is a 400 rather than a relayed
 * {@code #100}.
 */
public record SendNotificationRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "must be in E.164 format, e.g. +14155552671")
        String recipient,

        @NotBlank @Size(max = 255) String templateName,

        @Size(max = 20) String languageCode,

        List<@Size(max = 1024) String> bodyParams,

        Map<@Pattern(regexp = "^[a-z_][a-z0-9_]*$",
                message = "must be a template variable name: lowercase letters, digits and underscores, not starting with a digit")
                String,
            @NotNull @Size(max = 1024)
            @Pattern(regexp = "^(?!.* {5})[^\\r\\n\\t]*$",
                    message = "must not contain newlines, tabs or more than 4 consecutive spaces")
                String> namedBodyParams,

        @Size(max = 1024) String buttonUrlParam,

        // A text header's {{1}}. 60 is Meta's limit on the whole header line,
        // so a longer value can never be sent; checked here to make that a 400.
        List<@NotNull @Size(max = 60)
            @Pattern(regexp = "^[^\\r\\n\\t]*$", message = "must not contain newlines or tabs")
                String> headerParams
) {
    public SendNotificationRequest {
        bodyParams = bodyParams == null ? List.of() : List.copyOf(bodyParams);
        // Not List.copyOf, for the same reason as namedBodyParams below.
        headerParams = headerParams == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(headerParams));
        // Not Map.copyOf: that throws on a null value before validation can
        // report it as a field error, and it would lose the caller's order.
        namedBodyParams = namedBodyParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(namedBodyParams));
        languageCode = languageCode == null || languageCode.isBlank() ? "en" : languageCode;
    }

    // A bean property of its own rather than a getter for namedBodyParams:
    // sharing that name would collide with the record accessor in Jackson's
    // introspection. Reported as field "onlyOneKindOfBodyParams".
    @AssertTrue(message = "send either bodyParams (positional template) or namedBodyParams (named template), not both")
    public boolean isOnlyOneKindOfBodyParams() {
        return bodyParams.isEmpty() || namedBodyParams.isEmpty();
    }

    // Header params are positional only. A template written with named
    // variables names its header variable too, which this field cannot say.
    @AssertTrue(message = "headerParams fills a positional ({{1}}) header and cannot be combined with namedBodyParams")
    public boolean isHeaderParamsPositional() {
        return headerParams.isEmpty() || namedBodyParams.isEmpty();
    }
}
