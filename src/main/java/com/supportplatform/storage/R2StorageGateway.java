package com.supportplatform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/**
 * The only class in the codebase allowed to know R2's/S3's request shape
 * (Rule 4, applied to storage — storage-domain.md §3). R2 is S3-compatible
 * via a custom endpoint; MinIO (tests) speaks the identical protocol, so
 * this same class is exercised in both.
 */
@Component
public class R2StorageGateway implements StorageGateway {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public R2StorageGateway(@Value("${app.storage.endpoint}") String endpoint,
                             @Value("${app.storage.bucket}") String bucket,
                             @Value("${app.storage.access-key}") String accessKey,
                             @Value("${app.storage.secret-key}") String secretKey) {
        this.bucket = bucket;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        URI endpointUri = URI.create(endpoint);

        this.s3Client = S3Client.builder()
                .endpointOverride(endpointUri)
                .credentialsProvider(credentials)
                .region(Region.of("auto"))
                .forcePathStyle(true)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .credentialsProvider(credentials)
                .region(Region.of("auto"))
                // S3Client's forcePathStyle(true) has no equivalent presigner shortcut —
                // without this, presigned URLs come back virtual-hosted-style
                // (https://{bucket}.{endpoint-host}/...), which R2/MinIO don't serve.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public void upload(String objectKey, byte[] content, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public byte[] download(String objectKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray();
    }

    @Override
    public URI generatePresignedGetUrl(String objectKey, Duration ttl) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getObjectRequest)
                .build();
        return URI.create(s3Presigner.presignGetObject(presignRequest).url().toString());
    }
}
