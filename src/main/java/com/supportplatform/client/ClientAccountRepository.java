package com.supportplatform.client;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientAccountRepository extends JpaRepository<ClientAccount, UUID> {

    Optional<ClientAccount> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ClientAccount> findByTenantIdOrderByName(UUID tenantId);
}
