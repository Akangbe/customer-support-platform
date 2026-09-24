package com.supportplatform.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns durable, signature-verified {@link WebhookEvent} rows into domain
 * facts (whatsapp-domain.md §4), via {@link WebhookEventHandler}. Not
 * itself transactional — each event gets its own transaction through the
 * handler, so one event failing can't roll back another event's
 * already-processed messages in the same poll batch.
 *
 * <p>No timer of its own. {@link WhatsAppWorkScheduler} runs it when a
 * webhook arrives, when a retry falls due, and on a slow sweep; tests call
 * it directly.
 */
@Service
public class InboundEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);

    static final int BATCH_SIZE = 20;

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventHandler handler;

    InboundEventProcessor(WebhookEventRepository webhookEventRepository, WebhookEventHandler handler) {
        this.webhookEventRepository = webhookEventRepository;
        this.handler = handler;
    }

    /**
     * The handler records its own failures with a backoff, so a throw from
     * it is unexpected; it is still logged and skipped rather than allowed
     * to end the batch, for the reason {@link OutboundMessageSender#sendPending}
     * gives.
     *
     * @return how many events were handled without throwing; {@link #BATCH_SIZE}
     *         means more may be waiting
     */
    public int processPending() {
        List<WebhookEvent> pending = webhookEventRepository.findProcessable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        int handled = 0;
        for (WebhookEvent event : pending) {
            try {
                handler.handle(event.getId());
                handled++;
            } catch (Exception e) {
                log.warn("Webhook event {} could not be processed, left PENDING: {}", event.getId(), e.getMessage());
            }
        }
        return handled;
    }

    /** When the earliest backed-off event becomes due again, if any is waiting. */
    public Optional<Instant> nextRetryAt() {
        return webhookEventRepository.findNextRetryAt(Instant.now());
    }
}
