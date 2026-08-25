package com.supportplatform.whatsapp;

import java.net.URI;
import java.util.List;

/**
 * The boundary interface (whatsapp-integration.md §10, Rule 4). This is
 * the only shape any other module is allowed to depend on for sending —
 * {@link MetaWhatsAppGateway} is the only class permitted to import a
 * Meta HTTP/JSON shape.
 */
public interface WhatsAppGateway {

    SendResult sendText(WhatsAppConnection connection, String toPhone, String body);

    SendResult sendTemplate(WhatsAppConnection connection, String toPhone, String templateName,
                             String languageCode, List<String> params);

    /** Link-based, not upload-first (ADR-020) — {@code link} is a short-lived presigned storage URL Meta fetches itself. */
    SendResult sendMedia(WhatsAppConnection connection, String toPhone, String mediaType, URI link, String caption);

    /** Two Meta calls under the hood (resolve a temporary download URL, then fetch it) — storage-domain.md §6. */
    DownloadedMedia downloadMedia(WhatsAppConnection connection, String mediaId);

    /**
     * Exchanges an Embedded Signup authorization {@code code} for a Business
     * Integration System User access token, server-side (the app secret
     * never reaches the frontend) — ADR-011 Phase C. No {@link WhatsAppConnection}
     * param: none exists yet at this point in the flow.
     */
    OAuthExchangeResult exchangeCodeForToken(String code);

    /**
     * {@code POST /{waba-id}/subscribed_apps} — starts webhook delivery for
     * a newly authorized WABA. Phase A's one WABA got this manually via
     * Business Manager; Phase C connections need it done here. Best-effort:
     * {@code false} means the connection can still be created but won't
     * receive events until this is retried.
     */
    boolean subscribeToWaba(String wabaId, String accessToken);
}
