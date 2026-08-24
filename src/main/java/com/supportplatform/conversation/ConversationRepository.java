package com.supportplatform.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Conversation> findAllByTenantId(UUID tenantId, Pageable pageable);

    Page<Conversation> findAllByTenantIdAndStatus(UUID tenantId, ConversationStatus status, Pageable pageable);

    Optional<Conversation> findFirstByTenantIdAndCustomerIdAndStatusIn(UUID tenantId, UUID customerId, List<ConversationStatus> statuses);

    Optional<Conversation> findFirstByTenantIdAndCustomerIdAndStatusOrderByClosedAtDesc(UUID tenantId, UUID customerId, ConversationStatus status);
}
