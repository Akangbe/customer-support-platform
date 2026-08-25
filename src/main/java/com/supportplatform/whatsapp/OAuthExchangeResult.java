package com.supportplatform.whatsapp;

/** The outcome of an Embedded Signup code-for-token exchange — never a raw Meta shape (Rule 4). */
public record OAuthExchangeResult(boolean success, String accessToken, String errorDetail) {

    public static OAuthExchangeResult success(String accessToken) {
        return new OAuthExchangeResult(true, accessToken, null);
    }

    public static OAuthExchangeResult failure(String errorDetail) {
        return new OAuthExchangeResult(false, null, errorDetail);
    }
}
