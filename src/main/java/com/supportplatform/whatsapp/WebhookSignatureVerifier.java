package com.supportplatform.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies {@code X-Hub-Signature-256} (whatsapp-domain.md §3): HMAC-SHA256
 * of the raw request body, keyed by our Meta app secret. Comparison uses
 * {@link MessageDigest#isEqual} — a plain string comparison on a MAC is a
 * timing side-channel.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String SIGNATURE_PREFIX = "sha256=";

    private final byte[] appSecretBytes;

    public WebhookSignatureVerifier(@Value("${app.whatsapp.app-secret}") String appSecret) {
        this.appSecretBytes = appSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException malformedHeader) {
            return false;
        }
        return MessageDigest.isEqual(expected, hmacSha256(rawBody));
    }

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecretBytes, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }
}
