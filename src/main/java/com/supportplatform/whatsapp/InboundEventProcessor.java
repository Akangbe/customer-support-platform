package com.supportplatform.whatsapp;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Turns durable, signature-verified {@link WebhookEvent} rows into domain
 * facts (whatsapp-domain.md §4), via {@link WebhookEventHandler}. Not
 * itself transactional — each event gets its own transaction through the
 * handler, so one event failing can't roll back another event's
 * already-processed messages in the same poll batch.
 */
@Service
public class InboundEventProcessor {

    private static final int BATCH_SIZE = 20;

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventHandler handler;

    InboundEventProcessor(WebhookEventRepository webhookEventRepository, WebhookEventHandler handler) {
        this.webhookEventRepository = webhookEventRepository;
        this.handler = handler;
    }

    @Scheduled(fixedDelay = 2000)
    public void processPending() {
        List<WebhookEvent> pending = webhookEventRepository.findProcessable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (WebhookEvent event : pending) {
            handler.handle(event.getId());
        }
    }
}
