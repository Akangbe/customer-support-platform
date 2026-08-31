package com.supportplatform.storage;

import com.supportplatform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.net.URI;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared setup for storage tests: MinIO stands in for Cloudflare R2 —
 * same S3 protocol, same {@link R2StorageGateway} code path
 * (storage-domain.md §8), the same way the Postgres Testcontainer
 * already stands in for Neon.
 */
abstract class AbstractStorageIntegrationTest extends AbstractIntegrationTest {

    static final String BUCKET = "attachments-test";

    /** Same singleton-container reasoning as {@code AbstractIntegrationTest.POSTGRES} — started once, never stopped between test classes. */
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-04-08T15-41-24Z");

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.bucket", () -> BUCKET);
        registry.add("app.storage.access-key", MINIO::getUserName);
        registry.add("app.storage.secret-key", MINIO::getPassword);
    }

    /** MinIO doesn't create buckets on its own — a plain SDK call, independent of the Spring context, which isn't up yet at this point. */
    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .region(Region.of("auto"))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
        } catch (BucketAlreadyOwnedByYouException e) {
            // The MinIO container now outlives a single test class, so the
            // second class to run finds the bucket already there. Creating
            // it is meant to be idempotent setup, not an assertion.
        }
    }
}
