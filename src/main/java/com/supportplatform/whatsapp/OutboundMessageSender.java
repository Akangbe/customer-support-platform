package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The outbox consumer ADR-012 promised and message-domain.md deliberately
 * left unbuilt in Phase 5 (whatsapp-domain.md §6): finds {@code PENDING}
 * outbound messages and dispatches each through {@link MessageDispatcher}.
 * Not itself transactional — each message gets its own transaction via
 * the dispatcher, so one failure in a batch can't roll back another
 * message's already-successful send.
 *
 * <p>No timer of its own. {@link WhatsAppWorkScheduler} runs it when an
 * agent's message is queued, when a retry falls due, and on a slow sweep;
 * tests call it directly.
 */
@Service
public class OutboundMessageSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageSender.class);

    static final int BATCH_SIZE = 20;

    private final MessageRepository messageRepository;
    private final MessageDispatcher dispatcher;

    OutboundMessageSender(MessageRepository messageRepository, MessageDispatcher dispatcher) {
        this.messageRepository = messageRepository;
        this.dispatcher = dispatcher;
    }

    /**
     * One message that throws is logged and skipped, not allowed to end the
     * batch. The gateway turns Meta's errors into a failed {@link SendResult},
     * but anything else — a token that will not decrypt, storage refusing a
     * presigned URL — escapes as an exception, and because rows are taken
     * oldest first, one such message used to stall every message behind it,
     * for every tenant, on every run.
     *
     * @return how many messages were handled without throwing; {@link #BATCH_SIZE}
     *         means more may be waiting. A thrown message is not counted, so a
     *         batch of nothing but failures does not ask to be run again at once.
     */
    public int sendPending() {
        List<Message> sendable = messageRepository.findSendable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        int handled = 0;
        for (Message message : sendable) {
            try {
                dispatcher.dispatch(message.getId());
                handled++;
            } catch (Exception e) {
                log.warn("Outbound message {} could not be dispatched, left PENDING: {}", message.getId(), e.getMessage());
            }
        }
        return handled;
    }

    /** When the earliest backed-off message becomes due again, if any is waiting. */
    public Optional<Instant> nextRetryAt() {
        return messageRepository.findNextRetryAt(Instant.now());
    }
}
