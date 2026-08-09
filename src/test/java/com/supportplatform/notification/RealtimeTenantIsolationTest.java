package com.supportplatform.notification;

import com.supportplatform.conversation.dto.ConversationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves Rule 3's WebSocket-world equivalent (realtime-domain.md §3): subscribing to another tenant's topic gets nothing. */
class RealtimeTenantIsolationTest extends AbstractRealtimeIntegrationTest {

    @Test
    void subscribingToAnotherTenantsTopicReceivesNothing() throws Exception {
        MockHttpSession ownerA = registerTenantAndGetSession("RT Iso Tenant A", "Owner A", "rt-iso-a@example.com", "password123");
        MockHttpSession ownerB = registerTenantAndGetSession("RT Iso Tenant B", "Owner B", "rt-iso-b@example.com", "password123");
        UUID tenantB = extractTenantId(ownerB);

        StompSession stompSession = connectStomp(realLoginSessionCookie("rt-iso-a@example.com", "password123"));
        BlockingQueue<ConversationResponse> illegitimateQueue = subscribe(stompSession,
                "/topic/tenants/" + tenantB + "/conversations", ConversationResponse.class);

        String customerId = createCustomer(ownerB, "+14155557101", "Iso Customer");
        startConversation(ownerB, customerId); // a legitimate change on tenant B's own topic

        assertThat(illegitimateQueue.poll(2, TimeUnit.SECONDS)).isNull();
    }
}
