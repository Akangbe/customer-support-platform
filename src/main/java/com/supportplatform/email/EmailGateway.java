package com.supportplatform.email;

/**
 * The boundary interface (Rule 4's pattern applied to transactional email,
 * mirroring {@code StorageGateway} for object storage) — {@link
 * SesEmailGateway} is the only class permitted to know SES's request shape.
 */
public interface EmailGateway {

    void send(String to, String subject, String htmlBody, String textBody);
}
