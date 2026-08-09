package com.supportplatform.audit;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deliberately a plain {@code @EventListener}, not
 * {@code @TransactionalEventListener(AFTER_COMMIT)} like
 * {@code RealtimeEventListener} — Spring invokes this synchronously,
 * inside the same transaction as the action being audited (ADR-019).
 * If persisting the audit row fails, the whole transaction rolls back,
 * including the business mutation: an unaudited privileged action is a
 * worse outcome than a failed request the caller can retry.
 */
@Component
public class AuditLogListener {

    private final AuditLogRepository auditLogRepository;

    public AuditLogListener(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        auditLogRepository.save(AuditLogEntry.from(event));
    }
}
