package com.supportplatform.whatsapp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The Meta webhook endpoint (whatsapp-domain.md §3-4). No session — this
 * is machine-to-machine, authenticated by signature, not by login
 * (SecurityConfig permits both routes here). The body is read as raw
 * bytes, not bound to a DTO: HMAC verification needs the exact bytes
 * Meta signed, and a parsed-then-reserialized form is not guaranteed
 * byte-identical.
 */
@RestController
@RequestMapping("/api/v1/whatsapp/webhook")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;

    @Value("${app.whatsapp.verify-token}")
    private String verifyToken;

    public WhatsAppWebhookController(WebhookSignatureVerifier signatureVerifier, WebhookEventRepository webhookEventRepository) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
    }

    /** Meta's registration handshake. */
    @GetMapping
    public ResponseEntity<String> verify(@RequestParam("hub.mode") String mode,
                                          @RequestParam("hub.verify_token") String token,
                                          @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** Verify → persist → 200, in that order. Nothing about the domain happens before the row is durable (Rule 8). */
    @PostMapping
    public ResponseEntity<Void> receive(HttpServletRequest request,
                                         @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature)
            throws IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();

        if (!signatureVerifier.isValid(rawBody, signature)) {
            log.warn("Rejected WhatsApp webhook delivery with an invalid or missing signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        webhookEventRepository.save(WebhookEvent.received(new String(rawBody, StandardCharsets.UTF_8)));
        return ResponseEntity.ok().build();
    }
}
