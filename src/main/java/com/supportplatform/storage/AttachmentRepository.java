package com.supportplatform.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Optional<Attachment> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Attachment> findByMessageId(UUID messageId);

    List<Attachment> findByMessageIdIn(Collection<UUID> messageIds);
}
