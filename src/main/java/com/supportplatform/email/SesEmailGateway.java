package com.supportplatform.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * The only class permitted to know SES's request shape (Rule 4, mirrors
 * {@code R2StorageGateway}'s split for object storage). Credentials and
 * region resolve from the AWS SDK's default provider/region chains
 * (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION in this app's
 * deployments) rather than a bespoke {@code app.email} credential
 * property — nothing SES-specific to keep in sync with those two vars.
 *
 * <p>Client construction is lazy, and a construction failure (unset
 * credentials/region — the normal case in local dev and tests) degrades to
 * a logged warning instead of an app-startup failure, since {@link
 * InviteEmailListener} already treats delivery failure as non-fatal to the
 * invite operation that triggered it.
 */
@Component
public class SesEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SesEmailGateway.class);

    private final String source;
    private SesClient client;
    private boolean initFailed = false;

    public SesEmailGateway(@Value("${app.email.from-address}") String fromAddress,
                            @Value("${app.email.from-name}") String fromName) {
        this.source = fromName + " <" + fromAddress + ">";
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        SesClient sesClient = client();
        if (sesClient == null) {
            log.warn("SES not configured (missing AWS credentials/region) — email to {} not sent", to);
            return;
        }

        sesClient.sendEmail(SendEmailRequest.builder()
                .source(source)
                .destination(Destination.builder().toAddresses(to).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                .text(Content.builder().data(textBody).charset("UTF-8").build())
                                .build())
                        .build())
                .build());
    }

    private synchronized SesClient client() {
        if (client == null && !initFailed) {
            try {
                client = SesClient.create();
            } catch (SdkClientException e) {
                initFailed = true;
                log.warn("Failed to initialize SES client: {}", e.getMessage());
            }
        }
        return client;
    }
}
