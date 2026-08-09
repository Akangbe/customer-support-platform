package com.supportplatform.customer;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.customer.dto.CreateCustomerRequest;
import com.supportplatform.customer.dto.UpdateCustomerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for the Customer domain, same as TenantIsolationTest does for User. */
class CustomerTenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void customerListingIsScopedToTheCallersTenant() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Cust Tenant A", "Owner A", "cust-isolation-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Cust Tenant B", "Owner B", "cust-isolation-b@example.com", "password123");

        mockMvc.perform(post("/api/v1/customers").session(sessionA).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("+14155550201", "Customer A"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/customers").session(sessionB).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("+14155550202", "Customer B"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].phone").value("+14155550201"));

        mockMvc.perform(get("/api/v1/customers").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].phone").value("+14155550202"));
    }

    @Test
    void cannotReadAnotherTenantsCustomerByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Cust Tenant C", "Owner C", "cust-isolation-c@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Cust Tenant D", "Owner D", "cust-isolation-d@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155550203", "Customer D");

        mockMvc.perform(get("/api/v1/customers/" + customerBId).session(sessionA))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotUpdateAnotherTenantsCustomerByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Cust Tenant E", "Owner E", "cust-isolation-e@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Cust Tenant F", "Owner F", "cust-isolation-f@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155550204", "Customer F");

        mockMvc.perform(patch("/api/v1/customers/" + customerBId)
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCustomerRequest("Renamed"))))
                .andExpect(status().isNotFound());
    }
}
