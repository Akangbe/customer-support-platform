package com.supportplatform.message;

/** Outbound-only (domain-model.md) — inbound messages have no status. */
public enum MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
