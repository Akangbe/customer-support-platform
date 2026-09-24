package com.supportplatform.whatsapp;

import com.supportplatform.message.OutboundMessageQueued;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runs the inbound processor and outbound sender when there is work,
 * instead of asking the database every two seconds whether there is.
 *
 * <p>The two-second pollers were what exhausted the Neon quota on
 * 2026-09-24. Neon suspends a compute after about five idle minutes and
 * bills only while it runs; a query every two seconds meant it never went
 * idle for as long as the app was awake, so the month's free compute was
 * gone by the 24th and production could not open a connection.
 *
 * <p>Work now runs on three triggers:
 * <ul>
 *   <li><b>Arrival</b> — a webhook is stored, or an agent's message is
 *       queued. Handled at once, so latency is lower than the old two-second
 *       tick, not higher.</li>
 *   <li><b>Retry due</b> — after each run, one wake-up is timed to the
 *       earliest backed-off row (the 4^attempt backoff, at most minutes).</li>
 *   <li><b>Sweep</b> — once shortly after startup, which picks up anything
 *       left PENDING by a process that died mid-run, then at a slow interval
 *       as a backstop. Idle, this is the only database traffic.</li>
 * </ul>
 *
 * <p>One thread for both queues, as the Spring scheduler's single thread
 * was before: nothing here ever runs concurrently with itself, so the
 * processors keep the concurrency model they were written for.
 *
 * <p>Off when {@code app.scheduling.enabled=false}, like the timers it
 * replaces, so an integration test never races a background run on rows it
 * is asserting about. Tests call the processors directly instead.
 */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WhatsAppWorkScheduler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWorkScheduler.class);

    /** How long to wait before trying again after a run itself failed (the database unreachable, say). */
    private static final Duration AFTER_FAILURE = Duration.ofMinutes(1);

    /** A ceiling on batches per run, so a processor that stops making progress cannot hold the thread forever. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "whatsapp-work");
        thread.setDaemon(true);
        return thread;
    });

    private final Queue inbound;
    private final Queue outbound;

    public WhatsAppWorkScheduler(InboundEventProcessor inboundProcessor, OutboundMessageSender outboundSender) {
        this.inbound = new Queue("inbound", inboundProcessor::processPending,
                InboundEventProcessor.BATCH_SIZE, inboundProcessor::nextRetryAt);
        this.outbound = new Queue("outbound", outboundSender::sendPending,
                OutboundMessageSender.BATCH_SIZE, outboundSender::nextRetryAt);
    }

    /** {@code fallbackExecution}: the webhook controller has no transaction, so there is no commit to wait for. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWebhookReceived(WebhookEventReceived event) {
        inbound.requestNow();
    }

    /** After commit, so the sender never looks for a row that is not visible yet. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboundQueued(OutboundMessageQueued event) {
        outbound.requestNow();
    }

    @Scheduled(initialDelayString = "${app.whatsapp.work.startup-sweep-delay-ms:10000}",
               fixedDelayString = "${app.whatsapp.work.sweep-interval-ms:3600000}")
    public void sweep() {
        inbound.requestNow();
        outbound.requestNow();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** One queue's run-when-asked logic; requests that arrive while a run is already waiting merge into it. */
    private final class Queue {

        private final String name;
        private final IntSupplier batch;
        private final int batchSize;
        private final Supplier<Optional<Instant>> nextRetryAt;

        private final AtomicBoolean runWaiting = new AtomicBoolean();
        private final AtomicReference<Instant> wakeUpAt = new AtomicReference<>();

        Queue(String name, IntSupplier batch, int batchSize, Supplier<Optional<Instant>> nextRetryAt) {
            this.name = name;
            this.batch = batch;
            this.batchSize = batchSize;
            this.nextRetryAt = nextRetryAt;
        }

        void requestNow() {
            if (runWaiting.compareAndSet(false, true)) {
                executor.execute(this::run);
            }
        }

        /**
         * At most one wake-up outstanding per queue, moved earlier if an
         * earlier retry turns up. A later one is left alone: the run at the
         * earlier time re-reads the table and books the next.
         */
        void requestAt(Instant at) {
            Instant booked = wakeUpAt.get();
            if (booked != null && booked.isAfter(Instant.now()) && !at.isBefore(booked)) {
                return;
            }
            wakeUpAt.set(at);
            long delayMs = Math.max(0, Duration.between(Instant.now(), at).toMillis());
            executor.schedule(this::requestNow, delayMs, TimeUnit.MILLISECONDS);
        }

        private void run() {
            // Cleared before draining, not after: a request arriving mid-run
            // must queue another run, or its row could wait for the sweep.
            runWaiting.set(false);
            try {
                int batches = 0;
                while (batch.getAsInt() >= batchSize && ++batches < MAX_BATCHES_PER_RUN) {
                    // a full batch means more may be waiting
                }
                nextRetryAt.get().ifPresent(this::requestAt);
            } catch (Exception e) {
                log.warn("WhatsApp {} run failed, retrying in {}s: {}", name, AFTER_FAILURE.toSeconds(), e.getMessage());
                requestAt(Instant.now().plus(AFTER_FAILURE));
            }
        }
    }
}
