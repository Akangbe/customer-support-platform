package com.supportplatform.whatsapp;

/** Bytes and content type retrieved from Meta's Media API (whatsapp-domain.md-adjacent, storage-domain.md §6) — never a raw Meta shape (Rule 4). */
public record DownloadedMedia(byte[] content, String contentType) {
}
