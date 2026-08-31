package com.supportplatform.notification;

/**
 * The requested template is not on the caller's tenant allowlist, or is on
 * it but not currently approved. A 4xx — the caller can fix this — and
 * distinct from {@link NotificationDeliveryException}, which means we did
 * relay the send and Meta refused it.
 *
 * <p>The message names the template and its actual state, because that is
 * the tenant's own configuration and telling them is the entire point of
 * checking here rather than letting Meta reject it.
 */
public class TemplateNotAllowedException extends RuntimeException {

    public TemplateNotAllowedException(String message) {
        super(message);
    }

    public static TemplateNotAllowedException unknown(String templateName) {
        return new TemplateNotAllowedException("Template '" + templateName
                + "' is not registered for this tenant. Register it under the tenant's approved templates before sending.");
    }

    public static TemplateNotAllowedException notApproved(String templateName, Object status) {
        return new TemplateNotAllowedException("Template '" + templateName + "' is registered but its status is "
                + status + ", so it cannot be sent. Only APPROVED templates are sendable.");
    }
}
