package com.supportplatform.customer;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.customer.dto.CreateCustomerRequest;
import com.supportplatform.customer.dto.CustomerResponse;
import com.supportplatform.customer.dto.UpdateCustomerRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                    @Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = customerService.createManually(principal.getTenantId(), request.phone(), request.name());
        return CustomerResponse.from(customer);
    }

    @GetMapping
    public Page<CustomerResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                        @PageableDefault(size = 20) Pageable pageable) {
        return customerService.listByTenant(principal.getTenantId(), pageable).map(CustomerResponse::from);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return CustomerResponse.from(customerService.getWithinTenant(principal.getTenantId(), id));
    }

    @PatchMapping("/{id}")
    public CustomerResponse update(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id,
                                    @Valid @RequestBody UpdateCustomerRequest request) {
        Customer customer = customerService.updateProfile(principal.getTenantId(), id, request.name());
        return CustomerResponse.from(customer);
    }
}
