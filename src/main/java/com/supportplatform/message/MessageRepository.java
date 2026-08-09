package com.supportplatform.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findAllByTenantIdAndConversationId(UUID tenantId, UUID conversationId, Pageable pageable);

    Optional<Message> findByTenantIdAndWaMessageId(UUID tenantId, String waMessageId);
}
