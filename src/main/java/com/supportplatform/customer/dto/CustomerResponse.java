package com.supportplatform.customer.dto;

import com.supportplatform.customer.Customer;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String phone,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getPhone(), customer.getName(),
                customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
