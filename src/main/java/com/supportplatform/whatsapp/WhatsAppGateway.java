package com.supportplatform.whatsapp;

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
}
