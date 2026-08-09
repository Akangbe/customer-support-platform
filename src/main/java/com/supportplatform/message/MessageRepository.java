package com.supportplatform.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findAllByTenantIdAndConversationId(UUID tenantId, UUID conversationId, Pageable pageable);

    Optional<Message> findByTenantIdAndWaMessageId(UUID tenantId, String waMessageId);

    /** Outbound rows the sender poller should attempt now (whatsapp-domain.md §6). */
    @Query("""
            SELECT m FROM Message m
            WHERE m.direction = com.supportplatform.message.MessageDirection.OUTBOUND
              AND m.status = com.supportplatform.message.MessageStatus.PENDING
              AND (m.nextAttemptAt IS NULL OR m.nextAttemptAt <= :now)
            ORDER BY m.createdAt
            """)
    List<Message> findSendable(@Param("now") Instant now, Pageable pageable);
}
