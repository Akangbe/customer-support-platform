package com.supportplatform.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only class in the codebase allowed to know Meta's request/response
 * shape (Rule 4) — everything else speaks to {@link WhatsAppGateway}.
 * Calls {@code POST /{phone_number_id}/messages} on the Graph API using
 * the connection's own (decrypted) access token, never a shared/global
 * one.
 */
@Component
public class MetaWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppGateway.class);
    private static final Set<String> CAPTIONABLE_MEDIA_TYPES = Set.of("image", "video", "document");

    private final RestClient restClient;

    @Value("${app.whatsapp.graph-api-base-url}")
    private String graphApiBaseUrl;

    public MetaWhatsAppGateway(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public SendResult sendText(WhatsAppConnection connection, String toPhone, String body) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", "text",
                "text", Map.of("body", body)
        );
        return send(connection, payload);
    }

    @Override
    public SendResult sendTemplate(WhatsAppConnection connection, String toPhone, String templateName,
                                    String languageCode, List<String> params) {
        Map<String, Object> template = params.isEmpty()
                ? Map.of("name", templateName, "language", Map.of("code", languageCode))
                : Map.of("name", templateName, "language", Map.of("code", languageCode),
                        "components", List.of(Map.of(
                                "type", "body",
                                "parameters", params.stream().map(p -> Map.of("type", "text", "text", p)).toList()
                        )));

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", "template",
                "template", template
        );
        return send(connection, payload);
    }

    @Override
    public SendResult sendMedia(WhatsAppConnection connection, String toPhone, String mediaType, URI link, String caption) {
        Map<String, Object> mediaObject = new HashMap<>();
        mediaObject.put("link", link.toString());
        if (caption != null && !caption.isBlank() && CAPTIONABLE_MEDIA_TYPES.contains(mediaType)) {
            mediaObject.put("caption", caption);
        }

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", mediaType,
                mediaType, mediaObject
        );
        return send(connection, payload);
    }

    @Override
    public DownloadedMedia downloadMedia(WhatsAppConnection connection, String mediaId) {
        JsonNode metadata = restClient.get()
                .uri(graphApiBaseUrl + "/" + mediaId)
                .header("Authorization", "Bearer " + connection.getAccessToken())
                .retrieve()
                .body(JsonNode.class);

        String downloadUrl = metadata == null ? null : metadata.path("url").asText(null);
        if (downloadUrl == null) {
            throw new IllegalStateException("WhatsApp media metadata did not contain a download url for " + mediaId);
        }
        String contentType = metadata.path("mime_type").asText("application/octet-stream");

        byte[] content = restClient.get()
                .uri(downloadUrl)
                .header("Authorization", "Bearer " + connection.getAccessToken())
                .retrieve()
                .body(byte[].class);

        return new DownloadedMedia(content, contentType);
    }

    private SendResult send(WhatsAppConnection connection, Map<String, Object> payload) {
        String url = graphApiBaseUrl + "/" + connection.getPhoneNumberId() + "/messages";
        try {
            JsonNode response = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + connection.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            String waMessageId = response == null ? null : response.at("/messages/0/id").asText(null);
            if (waMessageId == null) {
                return SendResult.failure("WhatsApp response did not contain a message id");
            }
            return SendResult.success(waMessageId);
        } catch (RestClientException e) {
            log.warn("WhatsApp send failed for phone_number_id {}: {}", connection.getPhoneNumberId(), e.getMessage());
            return SendResult.failure(e.getMessage());
        }
    }
}
