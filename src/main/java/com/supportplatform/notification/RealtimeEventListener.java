package com.supportplatform.notification;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationChangedEvent;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.conversation.dto.ConversationResponse;
import com.supportplatform.message.Message;
import com.supportplatform.message.MessageEvent;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.dto.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * The only place that turns a domain event into a STOMP broadcast
 * (realtime-domain.md §4) — {@code conversation} and {@code message}
 * know nothing about WebSocket. Fires {@code AFTER_COMMIT} only: nobody
 * is ever told about a fact that could still roll back (Rule 2).
 * Re-fetches through the same tenant-scoped service methods the REST
 * layer uses and broadcasts the same DTOs the REST API returns — one
 * shape for both a fetch response and a socket push.
 *
 * <p>Broadcast failures are caught and logged, never rethrown: a
 * realtime delivery problem must not turn an already-committed,
 * successful business operation into a failed HTTP response for the
 * caller who triggered it.
 */
@Component
public class RealtimeEventListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ConversationService conversationService;
    private final MessageService messageService;

    public RealtimeEventListener(SimpMessagingTemplate messagingTemplate, ConversationService conversationService,
                                  MessageService messageService) {
        this.messagingTemplate = messagingTemplate;
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationChanged(ConversationChangedEvent event) {
        try {
            Conversation conversation = conversationService.getWithinTenant(event.tenantId(), event.conversationId());
            messagingTemplate.convertAndSend(topicFor(event.tenantId(), "conversations"), ConversationResponse.from(conversation));
        } catch (Exception e) {
            log.warn("Failed to broadcast conversation change for {}: {}", event.conversationId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageEvent(MessageEvent event) {
        try {
            Message message = messageService.getWithinTenant(event.tenantId(), event.messageId());
            messagingTemplate.convertAndSend(topicFor(event.tenantId(), "messages"), MessageResponse.from(message));
        } catch (Exception e) {
            log.warn("Failed to broadcast message event for {}: {}", event.messageId(), e.getMessage());
        }
    }

    private String topicFor(UUID tenantId, String segment) {
        return "/topic/tenants/" + tenantId + "/" + segment;
    }
}
