package com.jannetai.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local-disk implementation of {@link StorageService}, used until a real
 * AWS S3 implementation replaces it (explicit Phase 6 instruction: no real
 * AWS credentials required this phase). Writes under
 * {@code app.storage.local-base-path} (default {@code ./storage}, outside
 * source control - see .gitignore).
 *
 * NOT production-grade: no pre-signed URL issuance, no replication, no
 * access control beyond the filesystem itself, and files are lost if the
 * container/host is recreated. All of that is explicitly out of scope for
 * this phase and becomes the real S3 implementation's job - see
 * StorageService's Javadoc for the swap-in contract this class fulfills in
 * the meantime.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path baseDir;
    private final String signingSecret;

    public LocalStorageService(
            @Value("${app.storage.local-base-path:./storage}") String basePath,
            @Value("${app.storage.local-signing-secret:local-dev-image-signing-secret-change-me}") String signingSecret) {
        this.baseDir = Path.of(basePath).toAbsolutePath().normalize();
        this.signingSecret = signingSecret;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new StorageException("Could not initialize local storage directory: " + baseDir, e);
        }
        log.info("LocalStorageService initialized (stub, NOT S3) at {}", baseDir);
    }

    @Override
    public StoredObject store(MultipartFile file, String keyPrefix) {
        String extension = extensionFor(file.getContentType());
        String objectKey = "%s/%s-%s%s".formatted(
                keyPrefix, Instant.now().toEpochMilli(), UUID.randomUUID(), extension);

        Path target = baseDir.resolve(objectKey).normalize();
        // Defense in depth: keyPrefix is caller-controlled (built from a
        // numeric complaint ID, not raw user input) but this guards against
        // any future caller accidentally passing something path-traversal-
        // shaped straight through.
        if (!target.startsWith(baseDir)) {
            throw new StorageException("Resolved storage path escapes the storage root: " + objectKey, null);
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("Failed to store file at " + objectKey, e);
        }

        return new StoredObject(objectKey, file.getContentType(), file.getSize());
    }

    /** Phase 8 addition - see {@link StorageService#load}'s Javadoc for why this exists. */
    @Override
    public byte[] load(String storageKey) {
        Path source = baseDir.resolve(storageKey).normalize();
        if (!source.startsWith(baseDir)) {
            throw new StorageException("Resolved storage path escapes the storage root: " + storageKey, null);
        }
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new StorageException("Failed to read stored file at " + storageKey, e);
        }
    }

    /**
     * Gap-backlog Patch 6 (Sep 2026 audit): HMAC-signed, time-limited URL
     * against the new {@code ImageContentController} - a local-storage
     * analogue of a real S3 presigned URL. Query params are
     * {@code key} (the storage key, URL-encoded), {@code exp} (Unix
     * epoch-seconds expiry), and {@code sig} (hex HMAC-SHA256 over
     * {@code key + "." + exp}, keyed by {@link #signingSecret}) -
     * {@link #verifySignature} on the controller side is the exact
     * inverse of this method, so the two must stay in lockstep.
     *
     * <p>Deliberately a relative path (no scheme/host): Flutter already
     * knows the API base URL from its own config, exactly like every
     * other endpoint it calls - baking a host in here would just be
     * another thing to get wrong across dev/staging/prod.
     */
    @Override
    public String presignedUrl(String storageKey, Duration ttl) {
        long expiryEpochSeconds = Instant.now().plus(ttl).getEpochSecond();
        String signature = sign(storageKey, expiryEpochSeconds, signingSecret);
        return UriComponentsBuilder.fromPath("/api/v1/images/content")
                .queryParam("key", storageKey)
                .queryParam("exp", expiryEpochSeconds)
                .queryParam("sig", signature)
                .build()
                .toUriString();
    }

    /** Shared with {@code ImageContentController} for verification - see {@link #presignedUrl}'s Javadoc. */
    public static String sign(String storageKey, long expiryEpochSeconds, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((storageKey + "." + expiryEpochSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            // NoSuchAlgorithmException/InvalidKeyException: HmacSHA256 is a
            // guaranteed-available JCE algorithm and secret is never null
            // (has a default) - this is unreachable in practice, wrapped
            // only so the method signature doesn't force every caller to
            // handle checked exceptions for a case that can't occur.
            throw new StorageException("Failed to compute image URL signature", e);
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
