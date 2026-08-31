package com.supportplatform.audit;

/** FR-AUD-001–003 (audit-domain.md §2) — exactly the actions those three requirements name, not a general-purpose catch-all. */
public enum AuditAction {
    USER_INVITED,
    USER_ROLE_CHANGED,
    USER_DISABLED,
    USER_ENABLED,
    CONVERSATION_ASSIGNED,
    CONVERSATION_UNASSIGNED,
    WHATSAPP_CONNECTED,
    API_KEY_CREATED,
    API_KEY_DEACTIVATED,
    API_KEY_REACTIVATED
}
