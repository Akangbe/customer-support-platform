package com.supportplatform.audit;

import com.supportplatform.user.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Owner/Admin only (audit-domain.md §6) — auditing adds no new gate on performing an action, only on reading its record. */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogEntry> listByTenant(UUID tenantId, UserRole actingRole, Pageable pageable) {
        if (actingRole != UserRole.OWNER && actingRole != UserRole.ADMIN) {
            throw new AccessDeniedException("Only Owner or Admin can view the audit log");
        }
        return auditLogRepository.findAllByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }
}
