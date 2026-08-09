package com.supportplatform.customer;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.customer.dto.CreateCustomerRequest;
import com.supportplatform.customer.dto.UpdateCustomerRequest;
import com.supportplatform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerManagementTest extends AbstractIntegrationTest {

    @Autowired
    CustomerService customerService;

    @Autowired
    UserRepository userRepository;

    @Test
    void ownerCanCreateListGetAndUpdateACustomer() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Customer Co", "Customer Owner", "customer-owner-1@example.com", "password123");

        MvcResult createResult = mockMvc.perform(post("/api/v1/customers")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("+14155550101", "Ada Lovelace"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+14155550101"))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andReturn();
        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/customers").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].phone").value("+14155550101"));

        mockMvc.perform(get("/api/v1/customers/" + id).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ada Lovelace"));

        mockMvc.perform(patch("/api/v1/customers/" + id)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCustomerRequest("Ada, Countess of Lovelace"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ada, Countess of Lovelace"));
    }

    @Test
    void creatingWithADuplicatePhoneInTheSameTenantIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Dup Co", "Dup Owner", "dup-owner-1@example.com", "password123");

        mockMvc.perform(post("/api/v1/customers")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("+14155550102", "First"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("+14155550102", "Second"))))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedPhoneNumberIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Bad Phone Co", "Bad Phone Owner", "bad-phone-owner-1@example.com", "password123");

        mockMvc.perform(post("/api/v1/customers")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest("not-a-phone", "Someone"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gettingAnUnknownCustomerIsNotFound() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Missing Co", "Missing Owner", "missing-owner-1@example.com", "password123");

        mockMvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).session(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void findOrCreateFromInboundIsIdempotent() throws Exception {
        registerTenantAndGetSession("Inbound Co", "Inbound Owner", "inbound-owner-1@example.com", "password123");
        UUID tenantId = userRepository.findByEmail("inbound-owner-1@example.com").orElseThrow().getTenantId();

        Customer first = customerService.findOrCreateFromInbound(tenantId, "+14155550199", "From WhatsApp");
        Customer second = customerService.findOrCreateFromInbound(tenantId, "+14155550199", "From WhatsApp");

        assertThat(second.getId()).isEqualTo(first.getId());
    }
}
