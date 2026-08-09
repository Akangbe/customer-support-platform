package com.supportplatform.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsBySlug(String slug);
}
