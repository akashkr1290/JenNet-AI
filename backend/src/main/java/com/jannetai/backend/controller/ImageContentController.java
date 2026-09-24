package com.jannetai.backend.controller;

import com.jannetai.backend.storage.LocalStorageService;
import com.jannetai.backend.storage.StorageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Instant;

/**
 * Gap-backlog Patch 6 (Sep 2026 audit): serves image bytes for a
 * {@link LocalStorageService#presignedUrl} link. Deliberately public
 * (SecurityConfig permits {@code /api/v1/images/content} - see its
 * comment) rather than requiring a JWT: the point of a presigned-URL
 * model is that possessing the signed link itself is the authorization,
 * exactly like a real S3 presigned URL - the JWT-gated check already
 * happened once, earlier, when {@code ComplaintService.toResponse} (the
 * only place that calls {@code storageService.presignedUrl}) decided this
 * caller was allowed to see this complaint's images at all.
 *
 * <p>Only meaningful when {@code app.storage.provider=local}
 * (the default) - S3-issued presigned URLs point directly at AWS and
 * never reach this backend at all, so this controller is conditional on
 * the same property {@link LocalStorageService} is, and simply isn't
 * registered under {@code app.storage.provider=s3}.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
@Tag(name = "Images", description = "Signed-URL image content delivery (local storage mode only)")
public class ImageContentController {

    private final StorageService storageService;

    @Value("${app.storage.local-signing-secret:local-dev-image-signing-secret-change-me}")
    private String signingSecret;

    @GetMapping("/api/v1/images/content")
    public ResponseEntity<byte[]> getContent(
            @RequestParam("key") String storageKey,
            @RequestParam("exp") long expiryEpochSeconds,
            @RequestParam("sig") String signature) {

        if (Instant.now().getEpochSecond() > expiryEpochSeconds) {
            throw new ResponseStatusException(HttpStatus.GONE, "This image link has expired");
        }

        String expected = LocalStorageService.sign(storageKey, expiryEpochSeconds, signingSecret);
        // Constant-time comparison: a signed image link is a bearer
        // credential for the life of its TTL, so this deserves the same
        // timing-attack care as comparing a password hash or session
        // token, not a plain String.equals.
        boolean valid = expected.length() == signature.length()
                && MessageDigest.isEqual(
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid image link signature");
        }

        byte[] bytes = storageService.load(storageKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeFor(storageKey)))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(bytes);
    }

    /**
     * The presigned URL only carries the storage key, not the content
     * type (keeping the signed payload minimal) - inferred here from the
     * key's own extension, which {@link LocalStorageService#store} always
     * appends. Falls back to a generic binary type for the (currently
     * unreachable in practice) case of an extension-less key.
     */
    private String contentTypeFor(String storageKey) {
        String lower = storageKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
