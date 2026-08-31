package com.supportplatform.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The no-network implementation of the {@link WhatsAppGateway} boundary,
 * for demos, local development and tenant integration testing: every send
 * "succeeds" with a synthetic message id and nothing ever reaches Meta.
 *
 * <p>Swapped in for {@link MetaWhatsAppGateway} by
 * {@code app.whatsapp.mock=true} — off by default, and the property is
 * absent from {@code application-prod.yml} so a production deploy cannot
 * silently end up here and swallow real sends.
 *
 * <p>Synthetic ids carry a {@code wamid.MOCK-} prefix rather than looking
 * like real Meta ids, so a mock-mode row is obvious in
 * {@code notification_log} and in the {@code message} table afterwards.
 */
@Component
@ConditionalOnProperty(prefix = "app.whatsapp", name = "mock", havingValue = "true")
public class MockWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MockWhatsAppGateway.class);

    public MockWhatsAppGateway() {
        log.warn("WhatsApp mock mode is ACTIVE (app.whatsapp.mock=true) — no message will actually be delivered.");
    }

    @Override
    public SendResult sendText(WhatsAppConnection connection, String toPhone, String body) {
        log.info("[mock] text to {} via phone_number_id {}", toPhone, connection.getPhoneNumberId());
        return SendResult.success(mockMessageId());
    }

    @Override
    public SendResult sendTemplate(WhatsAppConnection connection, String toPhone, String templateName,
                                    String languageCode, List<String> params, String buttonUrlParam) {
        log.info("[mock] template '{}' ({}) to {} with {} body param(s){}", templateName, languageCode, toPhone,
                params.size(), buttonUrlParam == null ? "" : " and a url button param");
        return SendResult.success(mockMessageId());
    }

    @Override
    public SendResult sendMedia(WhatsAppConnection connection, String toPhone, String mediaType, URI link, String caption) {
        log.info("[mock] {} to {}", mediaType, toPhone);
        return SendResult.success(mockMessageId());
    }

    @Override
    public DownloadedMedia downloadMedia(WhatsAppConnection connection, String mediaId) {
        return new DownloadedMedia(("mock-media-" + mediaId).getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }

    @Override
    public OAuthExchangeResult exchangeCodeForToken(String code) {
        return OAuthExchangeResult.success("mock-access-token");
    }

    @Override
    public boolean subscribeToWaba(String wabaId, String accessToken) {
        log.info("[mock] subscribed to WABA {} webhooks", wabaId);
        return true;
    }

    private String mockMessageId() {
        return "wamid.MOCK-" + UUID.randomUUID();
    }
}
