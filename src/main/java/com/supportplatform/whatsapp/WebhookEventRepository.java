package com.supportplatform.whatsapp;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    @Query("""
            SELECT w FROM WebhookEvent w
            WHERE w.status = com.supportplatform.whatsapp.WebhookEventStatus.PENDING
              AND (w.nextAttemptAt IS NULL OR w.nextAttemptAt <= :now)
            ORDER BY w.receivedAt
            """)
    List<WebhookEvent> findProcessable(@Param("now") Instant now, Pageable pageable);
}
