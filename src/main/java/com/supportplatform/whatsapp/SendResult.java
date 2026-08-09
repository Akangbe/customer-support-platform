package com.supportplatform.whatsapp;

/** The outcome of a {@link WhatsAppGateway} send attempt — never a raw Meta shape (Rule 4). */
public record SendResult(boolean success, String waMessageId, String errorDetail) {

    public static SendResult success(String waMessageId) {
        return new SendResult(true, waMessageId, null);
    }

    public static SendResult failure(String errorDetail) {
        return new SendResult(false, null, errorDetail);
    }
}
