package com.supportplatform.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The pre-tenant lookup: a caller presents key_id before any tenant is known, so this resolves it (cf. {@code findByPhoneNumberId}). */
    Optional<ApiKey> findByKeyId(String keyId);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);
}
