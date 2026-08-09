package com.supportplatform.storage;

import java.net.URI;
import java.time.Duration;

/**
 * The boundary interface (storage-domain.md §3, Rule 4's pattern
 * applied to object storage) — {@link R2StorageGateway} is the only
 * class permitted to import an AWS SDK/S3 shape.
 */
public interface StorageGateway {

    void upload(String objectKey, byte[] content, String contentType);

    byte[] download(String objectKey);

    URI generatePresignedGetUrl(String objectKey, Duration ttl);
}
