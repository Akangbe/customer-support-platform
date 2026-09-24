package com.supportplatform.whatsapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The run-when-there-is-work logic on its own, against mocked processors.
 * The end-to-end behaviour with real rows is {@link WhatsAppWorkTriggerTest}.
 */
class WhatsAppWorkSchedulerTest {

    private final InboundEventProcessor inbound = mock(InboundEventProcessor.class);
    private final OutboundMessageSender outbound = mock(OutboundMessageSender.class);
    private final WhatsAppWorkScheduler scheduler = new WhatsAppWorkScheduler(inbound, outbound);

    @AfterEach
    void stop() {
        scheduler.shutdown();
    }

    @Test
    void aWebhookRunsTheInboundProcessorAndNothingElse() {
        when(inbound.nextRetryAt()).thenReturn(Optional.empty());

        scheduler.onWebhookReceived(new WebhookEventReceived());

        verify(inbound, timeout(2000)).processPending();
        verify(outbound, after(300).never()).sendPending();
    }

    @Test
    void aFullBatchIsFollowedByAnotherUntilTheQueueIsDrained() {
        // 20, 20, 3: two full batches say "more may be waiting", the short
        // one says the queue is empty.
        when(inbound.processPending()).thenReturn(InboundEventProcessor.BATCH_SIZE, InboundEventProcessor.BATCH_SIZE, 3);
        when(inbound.nextRetryAt()).thenReturn(Optional.empty());

        scheduler.onWebhookReceived(new WebhookEventReceived());

        verify(inbound, timeout(2000).times(3)).processPending();
        verify(inbound, after(300).times(3)).processPending();
    }

    @Test
    void aBackedOffRetryIsWokenForWhenItFallsDue() {
        when(inbound.processPending()).thenReturn(1, 0);
        when(inbound.nextRetryAt()).thenReturn(Optional.of(Instant.now().plusMillis(500)), Optional.empty());

        scheduler.onWebhookReceived(new WebhookEventReceived());

        // Once on arrival, once more when the retry falls due, with nobody
        // asking in between.
        verify(inbound, timeout(3000).times(2)).processPending();
    }

    @Test
    void requestsArrivingWhileARunIsWaitingMergeIntoIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(outbound.sendPending()).thenAnswer(invocation -> {
            release.await(2, TimeUnit.SECONDS);
            return 0;
        });
        when(outbound.nextRetryAt()).thenReturn(Optional.empty());

        // The first run starts and blocks; the next ten all land while one
        // follow-up run is already waiting, so they add nothing to it.
        scheduler.onOutboundQueued(null);
        verify(outbound, timeout(2000)).sendPending();
        for (int i = 0; i < 10; i++) {
            scheduler.onOutboundQueued(null);
        }
        release.countDown();

        verify(outbound, after(500).times(2)).sendPending();
    }

    @Test
    void aFailingRunDoesNotStopLaterOnes() {
        when(inbound.processPending()).thenThrow(new IllegalStateException("database unreachable")).thenReturn(0);
        when(inbound.nextRetryAt()).thenReturn(Optional.empty());

        scheduler.onWebhookReceived(new WebhookEventReceived());
        verify(inbound, timeout(2000)).processPending();

        scheduler.onWebhookReceived(new WebhookEventReceived());
        verify(inbound, timeout(2000).times(2)).processPending();
    }

    @Test
    void aBatchOfNothingButFailuresIsNotRerunInALoop() {
        // A processor that handled nothing (every row threw) reports 0, so
        // the run ends rather than re-reading the same rows fifty times.
        when(outbound.sendPending()).thenReturn(0);
        when(outbound.nextRetryAt()).thenReturn(Optional.empty());

        scheduler.onOutboundQueued(null);

        verify(outbound, after(500).times(1)).sendPending();
    }

    @Test
    void theSweepRunsBothQueues() {
        when(inbound.nextRetryAt()).thenReturn(Optional.empty());
        when(outbound.nextRetryAt()).thenReturn(Optional.empty());

        scheduler.sweep();

        verify(inbound, timeout(2000)).processPending();
        verify(outbound, timeout(2000)).sendPending();
    }

    @Test
    void nothingRunsUntilAskedTo() {
        verify(inbound, after(300).never()).processPending();
        verify(outbound, never()).sendPending();
    }
}
