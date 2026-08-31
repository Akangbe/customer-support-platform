package com.supportplatform.apikey;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditEvent;
import com.supportplatform.user.UserRole;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Issuing, listing and revoking tenant API keys. Owner/Admin only, on the
 * same reasoning as {@code WhatsAppConnectionService}: minting a
 * credential that can send on the tenant's WhatsApp number is workspace
 * configuration, never a Manager/Agent duty. Issuing and revoking are both
 * audited as configuration changes (FR-AUD-003).
 */
@Service
public class ApiKeyService {

    /** {@code rd_live_{keyId}.{secret}} — the prefix makes a leaked key greppable in logs and secret scanners. */
    static final String KEY_PREFIX = "rd_live_";
    static final String SECRET_SEPARATOR = ".";
    private static final int DEFAULT_RATE_LIMIT = 60;
    private static final int KEY_ID_BYTES = 8;
    private static final int SECRET_BYTES = 32;
    /** last_used_at is a coarse "is this key still in use" signal, not an access log — that is what notification_log is for. */
    private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, PasswordEncoder passwordEncoder,
                          ApplicationEventPublisher eventPublisher) {
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Mints a key for {@code tenantId} and returns the plaintext exactly
     * once. Only the hash of the secret half is persisted, so this return
     * value cannot be reconstructed afterwards — by us or by anyone holding
     * a database dump.
     */
    @Transactional
    public IssuedApiKey create(UUID tenantId, UUID actorUserId, UserRole actingRole, String name, Integer rateLimit) {
        requireOwnerOrAdmin(actingRole);

        String keyId = HexFormat.of().formatHex(randomBytes(KEY_ID_BYTES));
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(SECRET_BYTES));

        ApiKey apiKey = apiKeyRepository.save(new ApiKey(tenantId, keyId, passwordEncoder.encode(secret), name,
                rateLimit == null ? DEFAULT_RATE_LIMIT : rateLimit));

        // Never the secret, and never the plaintext key — audit-domain.md §2.
        eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, AuditAction.API_KEY_CREATED,
                "API_KEY", apiKey.getId(), "Issued API key (key_id=" + keyId + ")"));

        return new IssuedApiKey(apiKey, KEY_PREFIX + keyId + SECRET_SEPARATOR + secret);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listForTenant(UUID tenantId, UserRole actingRole) {
        requireOwnerOrAdmin(actingRole);
        return apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * The kill switch. Scoped by tenant, so one tenant can never reach
     * another's key (Rule 3). Idempotent: deactivating an already-inactive
     * key is a no-op rather than an error, so an operator hitting it twice
     * in a hurry does not get a failure back.
     *
     * <p>Takes effect on the very next request. {@link #authenticate} reads
     * {@code is_active} straight off the row on every call and nothing
     * about a key is cached anywhere, so there is no window in which a
     * deactivated key still works.
     */
    @Transactional
    public ApiKey deactivate(UUID tenantId, UUID actorUserId, UserRole actingRole, UUID apiKeyId) {
        requireOwnerOrAdmin(actingRole);

        ApiKey apiKey = requireOwnKey(tenantId, apiKeyId);
        if (apiKey.isActive()) {
            apiKey.deactivate();
            eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, AuditAction.API_KEY_DEACTIVATED,
                    "API_KEY", apiKey.getId(), "Deactivated API key (key_id=" + apiKey.getKeyId() + ")"));
        }
        return apiKey;
    }

    /** The other half of the switch, for when the tenant is cleared to resume. Also idempotent. */
    @Transactional
    public ApiKey reactivate(UUID tenantId, UUID actorUserId, UserRole actingRole, UUID apiKeyId) {
        requireOwnerOrAdmin(actingRole);

        ApiKey apiKey = requireOwnKey(tenantId, apiKeyId);
        if (!apiKey.isActive()) {
            apiKey.reactivate();
            eventPublisher.publishEvent(new AuditEvent(tenantId, actorUserId, AuditAction.API_KEY_REACTIVATED,
                    "API_KEY", apiKey.getId(), "Reactivated API key (key_id=" + apiKey.getKeyId() + ")"));
        }
        return apiKey;
    }

    private ApiKey requireOwnKey(UUID tenantId, UUID apiKeyId) {
        return apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "API key not found"));
    }

    /**
     * Authenticates a presented key, for {@link ApiKeyAuthenticationFilter}.
     * Returns empty for every failure mode — malformed, unknown key_id,
     * revoked key, wrong secret — so a caller cannot tell "no such key"
     * apart from "bad secret" and enumerate valid key ids.
     */
    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> authenticate(String presentedKey) {
        ParsedKey parsed = ParsedKey.parse(presentedKey);
        if (parsed == null) {
            return Optional.empty();
        }

        Optional<ApiKey> found = apiKeyRepository.findByKeyId(parsed.keyId());
        if (found.isEmpty() || !found.get().isActive()) {
            return Optional.empty();
        }

        ApiKey apiKey = found.get();
        // BCrypt's own comparison is constant-time, so a wrong secret costs
        // the same as a right one and leaks nothing through timing.
        if (!passwordEncoder.matches(parsed.secret(), apiKey.getSecretHash())) {
            return Optional.empty();
        }

        return Optional.of(new ApiKeyPrincipal(apiKey.getId(), apiKey.getTenantId(), apiKey.getKeyId(), apiKey.getName(),
                apiKey.getRateLimit()));
    }

    /**
     * Best-effort "last seen" stamp, in its own transaction so it commits
     * independently of whatever the request goes on to do (and can never
     * roll a send back). Throttled to at most one write per key per minute:
     * a row update on every request would be write amplification for a
     * field nothing reads at that resolution.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(UUID apiKeyId) {
        apiKeyRepository.findById(apiKeyId).ifPresent(apiKey -> {
            Instant now = Instant.now();
            if (apiKey.getLastUsedAt() == null
                    || Duration.between(apiKey.getLastUsedAt(), now).compareTo(LAST_USED_WRITE_INTERVAL) >= 0) {
                apiKey.markUsed(now);
            }
        });
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void requireOwnerOrAdmin(UserRole actingRole) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can manage API keys");
        }
    }

    /** The stored row plus the plaintext key, which exists only for the duration of the creating request. */
    public record IssuedApiKey(ApiKey apiKey, String plaintextKey) {
    }

    /** {@code rd_live_{keyId}.{secret}} split back into its two halves; {@code null} for anything malformed. */
    record ParsedKey(String keyId, String secret) {

        static ParsedKey parse(String presented) {
            if (presented == null || !presented.startsWith(KEY_PREFIX)) {
                return null;
            }
            String remainder = presented.substring(KEY_PREFIX.length());
            int separator = remainder.indexOf(SECRET_SEPARATOR);
            if (separator <= 0 || separator == remainder.length() - 1) {
                return null;
            }
            return new ParsedKey(remainder.substring(0, separator), remainder.substring(separator + 1));
        }
    }
}
