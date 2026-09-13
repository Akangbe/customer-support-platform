package com.supportplatform.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
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
@ConditionalOnProperty(prefix = "app.whatsapp", name = "mock", havingValue = "false", matchIfMissing = true)
public class MetaWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppGateway.class);
    private static final Set<String> CAPTIONABLE_MEDIA_TYPES = Set.of("image", "video", "document");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.whatsapp.graph-api-base-url}")
    private String graphApiBaseUrl;

    @Value("${app.whatsapp.app-id}")
    private String appId;

    @Value("${app.whatsapp.app-secret}")
    private String appSecret;

    public MetaWhatsAppGateway(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
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
                                    String languageCode, List<String> params, String buttonUrlParam) {
        List<Map<String, Object>> components = new ArrayList<>();
        if (!params.isEmpty()) {
            components.add(Map.of(
                    "type", "body",
                    "parameters", params.stream().map(p -> Map.of("type", "text", "text", p)).toList()
            ));
        }
        if (buttonUrlParam != null && !buttonUrlParam.isBlank()) {
            // Meta's shape for a dynamic URL button: index is the button's
            // position in the approved template, and the parameter is the
            // suffix appended to the URL registered there — not a whole URL.
            components.add(Map.of(
                    "type", "button",
                    "sub_type", "url",
                    "index", "0",
                    "parameters", List.of(Map.of("type", "text", "text", buttonUrlParam))
            ));
        }

        Map<String, Object> template = components.isEmpty()
                ? Map.of("name", templateName, "language", Map.of("code", languageCode))
                : Map.of("name", templateName, "language", Map.of("code", languageCode), "components", components);

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

    @Override
    public OAuthExchangeResult exchangeCodeForToken(String code) {
        try {
            JsonNode response = restClient.get()
                    .uri(graphApiBaseUrl + "/oauth/access_token?client_id={clientId}&client_secret={clientSecret}&code={code}",
                            appId, appSecret, code)
                    .retrieve()
                    .body(JsonNode.class);

            String accessToken = response == null ? null : response.path("access_token").asText(null);
            if (accessToken == null) {
                return OAuthExchangeResult.failure("Meta OAuth response did not contain an access token");
            }
            return OAuthExchangeResult.success(accessToken);
        } catch (RestClientException e) {
            log.warn("WhatsApp Embedded Signup code exchange failed: {}", e.getMessage());
            return OAuthExchangeResult.failure(e.getMessage());
        }
    }

    @Override
    public boolean subscribeToWaba(String wabaId, String accessToken) {
        try {
            restClient.post()
                    .uri(graphApiBaseUrl + "/{wabaId}/subscribed_apps", wabaId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("Failed to subscribe app to WABA {} webhooks: {}", wabaId, e.getMessage());
            return false;
        }
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
        } catch (RestClientResponseException e) {
            // Meta answered, and said no. Its own error code is the only thing
            // that distinguishes "retry in a moment" (131049 pacing, 613 rate
            // limit) from "a human must go and fix billing" (131042) — and
            // MetaBlockingError already keys the owner alert off exactly these
            // codes. Logging only e.getMessage() threw that away and left a
            // truncated string nobody could triage from.
            String detail = describe(e);
            log.warn("WhatsApp send REJECTED by Meta for phone_number_id {}: {}", connection.getPhoneNumberId(), detail);
            return SendResult.failure(detail);
        } catch (RestClientException e) {
            // No usable answer: connect timeout, read timeout, DNS, TLS. Worth
            // separating from the above because the send may well have been
            // received and acted on by Meta — we simply never heard. Retrying
            // this case is what duplicates a message.
            String detail = "No response from Meta (" + e.getClass().getSimpleName() + "): " + e.getMessage();
            log.warn("WhatsApp send UNANSWERED for phone_number_id {} — delivery is UNKNOWN, not failed: {}",
                    connection.getPhoneNumberId(), detail);
            return SendResult.failure(detail);
        }
    }

    /**
     * Flattens Meta's error envelope into one triage-ready line. Meta
     * returns {@code {"error":{"message","type","code","error_subcode",
     * "fbtrace_id"}}}; {@code fbtrace_id} is what Meta support asks for
     * first, so it is kept rather than dropped.
     *
     * <p>Falls back to the raw body when the shape is unexpected, and caps
     * its length — an HTML error page from a proxy in front of the Graph API
     * should not put kilobytes into a log line or a {@code failure_reason}
     * column.
     */
    private String describe(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return "HTTP " + e.getStatusCode().value()
                        + " code=" + error.path("code").asText("?")
                        + " subcode=" + error.path("error_subcode").asText("-")
                        + " type=" + error.path("type").asText("?")
                        + " fbtrace=" + error.path("fbtrace_id").asText("-")
                        + " message=" + error.path("message").asText("");
            }
        } catch (Exception parseFailure) {
            // fall through to the raw body
        }
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500) + "...[truncated]";
        }
        return "HTTP " + e.getStatusCode().value() + " body=" + trimmed;
    }
}
