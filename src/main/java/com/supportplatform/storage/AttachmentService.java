package com.supportplatform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Upload and retrieval of tenant-scoped attachments (storage-domain.md
 * §2, §4, §7). Tenant scoping is enforced here, not trusted from the
 * caller — every lookup goes through {@code findByIdAndTenantId}.
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final StorageGateway storageGateway;
    private final Duration presignedUrlTtl;

    public AttachmentService(AttachmentRepository attachmentRepository, StorageGateway storageGateway,
                              @Value("${app.storage.presigned-url-ttl}") Duration presignedUrlTtl) {
        this.attachmentRepository = attachmentRepository;
        this.storageGateway = storageGateway;
        this.presignedUrlTtl = presignedUrlTtl;
    }

    /** The object key embeds the attachment's own id (storage-domain.md §2) — generated here, before the upload, not left to the database. */
    @Transactional
    public Attachment upload(UUID tenantId, byte[] content, String contentType, String fileName) {
        UUID id = UUID.randomUUID();
        String safeFileName = fileName == null || fileName.isBlank() ? "file" : fileName;
        String objectKey = "tenants/" + tenantId + "/attachments/" + id + "/" + safeFileName;

        storageGateway.upload(objectKey, content, contentType);

        Attachment attachment = new Attachment(id, tenantId, objectKey, contentType, fileName, content.length);
        return attachmentRepository.save(attachment);
    }

    @Transactional(readOnly = true)
    public Attachment getWithinTenant(UUID tenantId, UUID attachmentId) {
        return attachmentRepository.findByIdAndTenantId(attachmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Attachment not found"));
    }

    @Transactional(readOnly = true)
    public URI presignedUrlFor(Attachment attachment) {
        return storageGateway.generatePresignedGetUrl(attachment.getObjectKey(), presignedUrlTtl);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findAttachmentIdForMessage(UUID messageId) {
        return attachmentRepository.findByMessageId(messageId).map(Attachment::getId);
    }

    @Transactional(readOnly = true)
    public Optional<Attachment> findByMessageId(UUID messageId) {
        return attachmentRepository.findByMessageId(messageId);
    }

    /** Batched to avoid one query per message when rendering a page of {@code MessageResponse}s. */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> findAttachmentIdsByMessageIds(Collection<UUID> messageIds) {
        Map<UUID, UUID> byMessageId = new HashMap<>();
        for (Attachment attachment : attachmentRepository.findByMessageIdIn(messageIds)) {
            byMessageId.put(attachment.getMessageId(), attachment.getId());
        }
        return byMessageId;
    }

    public Duration getPresignedUrlTtl() {
        return presignedUrlTtl;
    }
}
