package com.supportplatform.whatsapp;

import com.supportplatform.conversation.ConversationRepository;
import com.supportplatform.customer.Customer;
import com.supportplatform.customer.CustomerRepository;
import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.storage.Attachment;
import com.supportplatform.storage.AttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Covers storage-domain.md §6: an inbound media message is downloaded from Meta, stored, and linked to its Message. */
class WhatsAppMediaInboundTest extends AbstractWhatsAppMediaIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private InboundEventProcessor inboundEventProcessor;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    @Test
    void anInboundImageMessageIsDownloadedStoredAndLinked() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Media In Co 1", "Media In Owner 1", "media-in-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "media-in-pn-1");

        byte[] fakeImageBytes = "fake-jpeg-bytes-from-meta".getBytes();
        when(gateway.downloadMedia(any(), anyString())).thenReturn(new DownloadedMedia(fakeImageBytes, "image/jpeg"));

        postSignedWebhook(inboundImagePayload(phoneNumberId, "15551119201", "wamid.IMGIN1", "media-id-abc", "check this out"));
        inboundEventProcessor.processPending();

        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, "+15551119201").orElseThrow();
        var conversation = conversationRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent().get(0);
        assertThat(conversation.getCustomerId()).isEqualTo(customer.getId());

        Message message = messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.IMGIN1").orElseThrow();
        assertThat(message.getBody()).isEqualTo("check this out");

        Attachment attachment = attachmentRepository.findByMessageId(message.getId()).orElseThrow();
        assertThat(attachment.getContentType()).isEqualTo("image/jpeg");
        assertThat(attachment.getSizeBytes()).isEqualTo(fakeImageBytes.length);
    }

    private String inboundImagePayload(String phoneNumberId, String fromDigits, String waMessageId, String mediaId, String caption) {
        return """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "waba-1",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {"phone_number_id": "%s"},
                            "contacts": [],
                            "messages": [
                              {"from": "%s", "id": "%s", "timestamp": "1700000000", "type": "image",
                               "image": {"id": "%s", "mime_type": "image/jpeg", "caption": "%s"}}
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, fromDigits, waMessageId, mediaId, caption);
    }
}
