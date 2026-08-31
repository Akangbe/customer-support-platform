package com.supportplatform.apikey;

import com.supportplatform.apikey.dto.ApiKeyResponse;
import com.supportplatform.apikey.dto.CreateApiKeyRequest;
import com.supportplatform.apikey.dto.CreatedApiKeyResponse;
import com.supportplatform.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Key management for a logged-in Owner/Admin — session-authenticated on
 * the existing filter chain, unlike {@code /api/v1/notifications/**} which
 * the keys issued here authenticate against.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** The only response that ever carries the plaintext key. Not retrievable afterwards. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                          @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyService.IssuedApiKey issued = apiKeyService.create(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), request.name(), request.rateLimit());
        return CreatedApiKeyResponse.of(issued.apiKey(), issued.plaintextKey());
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return apiKeyService.listForTenant(principal.getTenantId(), principal.getRole()).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    /**
     * The safety valve: stops this key sending immediately, without
     * destroying it. Separate verbs rather than a DELETE, because the state
     * is reversible and an operator needs to be able to say which direction
     * they meant.
     */
    @PostMapping("/{apiKeyId}/deactivate")
    public ApiKeyResponse deactivate(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                       @PathVariable UUID apiKeyId) {
        return ApiKeyResponse.from(apiKeyService.deactivate(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), apiKeyId));
    }

    @PostMapping("/{apiKeyId}/activate")
    public ApiKeyResponse activate(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                     @PathVariable UUID apiKeyId) {
        return ApiKeyResponse.from(apiKeyService.reactivate(principal.getTenantId(), principal.getUserId(),
                principal.getRole(), apiKeyId));
    }
}
