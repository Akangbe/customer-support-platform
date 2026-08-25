package com.supportplatform.whatsapp;

/** Thrown when Meta's Embedded Signup code-for-token exchange fails (expired/invalid code, revoked grant, Meta API error). */
public class WhatsAppCodeExchangeException extends RuntimeException {

    public WhatsAppCodeExchangeException(String message) {
        super(message);
    }
}
