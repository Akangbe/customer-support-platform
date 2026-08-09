package com.supportplatform.audit;

import com.supportplatform.audit.dto.AuditLogEntryResponse;
import com.supportplatform.auth.AuthenticatedPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Page<AuditLogEntryResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                             @PageableDefault(size = 20) Pageable pageable) {
        return auditLogService.listByTenant(principal.getTenantId(), principal.getRole(), pageable)
                .map(AuditLogEntryResponse::from);
    }
}
