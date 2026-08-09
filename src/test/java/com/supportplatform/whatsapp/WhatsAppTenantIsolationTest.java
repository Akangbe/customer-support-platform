package com.supportplatform.whatsapp;

import com.supportplatform.customer.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for the WhatsApp domain, same as every other phase's isolation test. */
class WhatsAppTenantIsolationTest extends AbstractWhatsAppIntegrationTest {

    @Autowired
    private InboundEventProcessor inboundEventProcessor;
    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void connectingWhatsAppForOneTenantDoesNotAffectAnothersConnection() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Iso WA Tenant A", "Owner A", "iso-wa-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Iso WA Tenant B", "Owner B", "iso-wa-b@example.com", "password123");

        connectWhatsApp(sessionA, "iso-pn-a");
        connectWhatsApp(sessionB, "iso-pn-b");

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("iso-pn-a"));

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("iso-pn-b"));
    }

    @Test
    void anInboundMessageForOneTenantsPhoneNumberIdNeverCreatesRecordsInAnotherTenant() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Iso WA Tenant C", "Owner C", "iso-wa-c@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Iso WA Tenant D", "Owner D", "iso-wa-d@example.com", "password123");
        UUID tenantB = extractTenantId(sessionB);

        connectWhatsApp(sessionA, "iso-pn-c");
        connectWhatsApp(sessionB, "iso-pn-d");

        postSignedWebhook("""
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "waba-1",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {"phone_number_id": "iso-pn-c"},
                            "contacts": [],
                            "messages": [
                              {"from": "15551119999", "id": "wamid.ISO1", "timestamp": "1700000000", "type": "text", "text": {"body": "hi"}}
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """);
        inboundEventProcessor.processPending();

        assertThat(customerRepository.findByTenantIdAndPhone(tenantB, "+15551119999")).isEmpty();
    }
}
