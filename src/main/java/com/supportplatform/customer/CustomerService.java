package com.supportplatform.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Customer identity and profile management (customer-domain.md). Tenant
 * scoping is enforced here, not trusted from the caller — every lookup
 * goes through {@code findByIdAndTenantId} / {@code findByTenantIdAndPhone}.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /** Explicit agent action: rejects if the phone is already known in this tenant. */
    @Transactional
    public Customer createManually(UUID tenantId, String phone, String name) {
        String normalized = normalize(phone);
        if (customerRepository.findByTenantIdAndPhone(tenantId, normalized).isPresent()) {
            throw new CustomerAlreadyExistsException(normalized);
        }
        return customerRepository.save(new Customer(tenantId, normalized, name));
    }

    /**
     * Idempotent: returns the existing customer for this phone if known,
     * otherwise creates one. This is the operation Phase 6's WhatsApp
     * webhook handler calls on every inbound message — it must never fail
     * just because the customer already exists.
     */
    @Transactional
    public Customer findOrCreateFromInbound(UUID tenantId, String phone, String name) {
        String normalized = normalize(phone);
        return customerRepository.findByTenantIdAndPhone(tenantId, normalized)
                .orElseGet(() -> customerRepository.save(new Customer(tenantId, normalized, name)));
    }

    @Transactional(readOnly = true)
    public Customer getWithinTenant(UUID tenantId, UUID customerId) {
        return customerRepository.findByIdAndTenantId(customerId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Customer not found"));
    }

    @Transactional(readOnly = true)
    public Page<Customer> listByTenant(UUID tenantId, Pageable pageable) {
        return customerRepository.findAllByTenantId(tenantId, pageable);
    }

    @Transactional
    public Customer updateProfile(UUID tenantId, UUID customerId, String name) {
        Customer customer = getWithinTenant(tenantId, customerId);
        customer.rename(name);
        return customer;
    }

    private String normalize(String phone) {
        return phone.trim();
    }
}
