package com.supportplatform.apikey.dto;

import com.supportplatform.apikey.ApiKey;

/**
 * The one and only response that carries {@code apiKey} in full. We store
 * a hash of the secret half and nothing else, so this value is
 * unrecoverable the moment the response is written — the caller has to
 * save it now or issue a new key.
 */
public record CreatedApiKeyResponse(ApiKeyResponse key, String apiKey) {

    public static CreatedApiKeyResponse of(ApiKey stored, String plaintextKey) {
        return new CreatedApiKeyResponse(ApiKeyResponse.from(stored), plaintextKey);
    }
}
