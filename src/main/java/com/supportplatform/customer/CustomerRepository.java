package com.supportplatform.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByTenantIdAndPhone(UUID tenantId, String phone);

    Optional<Customer> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Customer> findAllByTenantId(UUID tenantId, Pageable pageable);
}
