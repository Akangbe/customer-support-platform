package com.supportplatform.auth.passwordreset;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs reset requests away from the HTTP thread. Sending an email takes time
 * that looking up an unknown address does not, so a response that waited for
 * it would show which addresses have accounts.
 *
 * <p>The endpoint needs no login, so the queue is bounded. When it is full a
 * request is dropped, and the person can simply ask again.
 */
@Component
public class PasswordResetRequests {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequests.class);
    private static final int QUEUE_CAPACITY = 200;

    private final PasswordResetService service;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY));

    public PasswordResetRequests(PasswordResetService service) {
        this.service = service;
    }

    public void submit(String email) {
        try {
            executor.execute(() -> {
                try {
                    service.request(email);
                } catch (RuntimeException e) {
                    log.warn("Password reset request failed: {}", e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Password reset request dropped: the queue is full");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
