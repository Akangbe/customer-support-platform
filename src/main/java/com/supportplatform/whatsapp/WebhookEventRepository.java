package com.supportplatform.whatsapp;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    @Query("""
            SELECT w FROM WebhookEvent w
            WHERE w.status = com.supportplatform.whatsapp.WebhookEventStatus.PENDING
              AND (w.nextAttemptAt IS NULL OR w.nextAttemptAt <= :now)
            ORDER BY w.receivedAt
            """)
    List<WebhookEvent> findProcessable(@Param("now") Instant now, Pageable pageable);

    /** The earliest backed-off retry still in the future — what the next wake-up is timed to. */
    @Query("""
            SELECT min(w.nextAttemptAt) FROM WebhookEvent w
            WHERE w.status = com.supportplatform.whatsapp.WebhookEventStatus.PENDING
              AND w.nextAttemptAt > :now
            """)
    Optional<Instant> findNextRetryAt(@Param("now") Instant now);
}
