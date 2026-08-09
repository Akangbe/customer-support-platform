package com.supportplatform.storage.dto;

import java.time.Instant;

/** A time-boxed URL, not a byte proxy (storage-domain.md §7) — the backend hands this out and steps out of the way. */
public record AttachmentUrlResponse(String url, Instant expiresAt) {
}
