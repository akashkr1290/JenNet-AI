package com.jannetai.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Gap-backlog Patches 5/7 (Sep 2026 audit): the real AWS S3 implementation
 * {@link StorageService}'s own Javadoc always described as a future
 * phase's job - "no caller (ComplaintService) changes when that happens".
 * That held: ComplaintService, ImageResponse, and every other caller of
 * this interface are completely unaware whether {@link LocalStorageService}
 * or this class is the active bean.
 *
 * <p>Activated by {@code app.storage.provider=s3} (see
 * {@code application.yml}/{@code application-prod.yml}); credentials are
 * never read from config here - {@link S3Client#create()}'s default
 * credential provider chain picks them up from the EC2 instance role
 * (deployment/aws/terraform/iam.tf's {@code s3_media_access} policy),
 * exactly matching this project's existing "no AWS access keys anywhere
 * in this repo or on the host" convention (see iam.tf's own header
 * comment).
 *
 * <p>NOT VERIFIED against a live S3 bucket: no reachable AWS endpoint in
 * this sandbox (see network_configuration) - validated by manual review
 * against the AWS SDK v2 API only, same constraint every other
 * AWS-integration file in this project already documents.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3StorageService(
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.region:ap-south-1}") String region) {
        this.bucket = bucket;
        Region awsRegion = Region.of(region);
        this.s3Client = S3Client.builder().region(awsRegion).build();
        this.s3Presigner = S3Presigner.builder().region(awsRegion).build();
        log.info("S3StorageService initialized for bucket {} in region {}", bucket, region);
    }

    @Override
    public StoredObject store(MultipartFile file, String keyPrefix) {
        String extension = extensionFor(file.getContentType());
        String objectKey = "%s/%s-%s%s".formatted(
                keyPrefix, Instant.now().toEpochMilli(), UUID.randomUUID(), extension);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    // Server-side encryption is already the bucket default
                    // (s3.tf's server_side_encryption_configuration), so
                    // not repeated per-request here.
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new StorageException("Failed to read upload for S3 storage at " + objectKey, e);
        } catch (S3Exception e) {
            throw new StorageException("S3 rejected upload at " + objectKey, e);
        }

        return new StoredObject(objectKey, file.getContentType(), file.getSize());
    }

    @Override
    public byte[] load(String storageKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (S3Exception e) {
            throw new StorageException("Failed to read object from S3 at " + storageKey, e);
        }
    }

    /**
     * A genuine AWS presigned GET URL, pointing directly at S3 - unlike
     * {@link LocalStorageService#presignedUrl}, this backend is not in the
     * request path once the URL is issued.
     */
    @Override
    public String presignedUrl(String storageKey, Duration ttl) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getRequest)
                    .build();
            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            return presigned.url().toString();
        } catch (S3Exception e) {
            throw new StorageException("Failed to presign S3 URL for " + storageKey, e);
        }
    }

    private String extensionFor(String contentType) {
        if (contentType == null) {
            return "";
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
