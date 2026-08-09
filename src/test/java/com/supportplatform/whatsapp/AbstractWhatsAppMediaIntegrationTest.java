package com.supportplatform.whatsapp;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * WhatsApp media tests need both a WhatsApp connection (
 * {@link AbstractWhatsAppIntegrationTest}) and object storage — Java has
 * no multiple inheritance, and only these two test classes need both, so
 * this small duplication of {@code storage.AbstractStorageIntegrationTest}'s
 * MinIO setup is cheaper than restructuring the shared base every other
 * WhatsApp test would then also pay a MinIO-startup cost for.
 */
abstract class AbstractWhatsAppMediaIntegrationTest extends AbstractWhatsAppIntegrationTest {

    static final String BUCKET = "whatsapp-media-test";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-04-08T15-41-24Z");

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.bucket", () -> BUCKET);
        registry.add("app.storage.access-key", MINIO::getUserName);
        registry.add("app.storage.secret-key", MINIO::getPassword);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .region(Region.of("auto"))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
        }
    }
}
