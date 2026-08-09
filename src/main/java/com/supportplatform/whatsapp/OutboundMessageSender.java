package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The outbox consumer ADR-012 promised and message-domain.md deliberately
 * left unbuilt in Phase 5 (whatsapp-domain.md §6): finds {@code PENDING}
 * outbound messages and dispatches each through {@link MessageDispatcher}.
 * Not itself transactional — each message gets its own transaction via
 * the dispatcher, so one failure in a batch can't roll back another
 * message's already-successful send.
 */
@Service
public class OutboundMessageSender {

    private static final int BATCH_SIZE = 20;

    private final MessageRepository messageRepository;
    private final MessageDispatcher dispatcher;

    OutboundMessageSender(MessageRepository messageRepository, MessageDispatcher dispatcher) {
        this.messageRepository = messageRepository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelay = 2000)
    public void sendPending() {
        List<Message> sendable = messageRepository.findSendable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (Message message : sendable) {
            dispatcher.dispatch(message.getId());
        }
    }
}
