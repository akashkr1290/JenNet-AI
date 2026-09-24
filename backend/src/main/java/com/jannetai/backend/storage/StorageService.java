package com.jannetai.backend.storage;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Media storage abstraction (PROJECT_INTEGRATION.md Section 5, "Media
 * Storage Contract"): MySQL never stores binary image bytes, only the
 * object key/path + metadata; the actual file bytes live behind whatever
 * implements this interface.
 *
 * Phase 6 provides {@link LocalStorageService} - a local-disk stub - so
 * complaint photo upload works end-to-end without requiring real AWS
 * credentials in this environment (explicit instruction for this phase).
 * A future phase swaps in an S3-backed implementation
 * (`AWS_S3_BUCKET`/credentials already reserved in `.env.example`) behind
 * this same interface; no caller (ComplaintService) changes when that
 * happens - that's the entire point of the abstraction.
 */
public interface StorageService {

    /**
     * Persists the given file's bytes and returns a {@link StoredObject}
     * describing where it landed. The returned {@code storageKey} is an
     * internal object key/path only - never a public URL - consistent with
     * the media storage contract ("access is via backend-issued pre-signed
     * URLs at read time", not yet implemented for the local stub - see
     * LocalStorageService's Javadoc).
     *
     * @param file      the uploaded multipart file (already validated by
     *                  the caller - content type, size)
     * @param keyPrefix logical grouping for the object key, e.g.
     *                  {@code "complaints/42"} - callers own the naming
     *                  convention, this method owns making it unique
     */
    StoredObject store(MultipartFile file, String keyPrefix);

    /**
     * Reads back the raw bytes of a previously-stored object.
     *
     * ADDED Phase 8: resolves the open item flagged in ARCHITECTURE.md
     * Section 9 ("ai-service has no fetchable image URL to call the
     * backend with yet") - rather than growing a temporary public/signed
     * URL-issuing capability on {@link LocalStorageService} (a bigger,
     * riskier change for a local-disk stub that was never meant to be
     * internet-reachable), Phase 8 has the backend read the file itself
     * and send the bytes to {@code ai-service} as {@code image_base64} -
     * the alternate input {@code ai-service} already accepts (Phase 7,
     * {@code app/core/image_fetch.py}). See
     * {@code service/complaint/AiClassificationService}'s Javadoc and
     * PROJECT_INTEGRATION.md Section 5/6 for the full decision record.
     * The real {@code image_url} path stays implemented on the
     * {@code ai-service} side but remains unexercised by this backend -
     * unchanged from Phase 7.
     *
     * @param storageKey the opaque key/path returned by a prior
     *                   {@link #store} call (never a public URL)
     * @throws StorageException if the object cannot be read
     */
    byte[] load(String storageKey);

    /**
     * Gap-backlog Patch 6/7 (Sep 2026 audit): a short-lived, unguessable
     * URL an already-authorized caller (a citizen viewing their own
     * complaint, or staff with department/role access to it - see
     * {@code ComplaintService.toResponse}, the only caller) can hand
     * straight to Flutter's image widget, closing the "images should not
     * be publicly accessible" requirement without changing
     * {@code storageKey}'s own contract (still never a public URL by
     * itself).
     *
     * <p>The real S3 implementation ({@link S3StorageService}) issues a
     * genuine AWS presigned GET URL. The local-disk stub
     * ({@link LocalStorageService}) issues an HMAC-signed URL against this
     * backend's own new {@code /api/v1/images/content} endpoint instead -
     * see that class's Javadoc for why a real presigned-URL *contract* was
     * worth implementing even for local/dev storage, rather than only
     * shipping it behind S3.
     *
     * @param storageKey the opaque key/path returned by a prior
     *                    {@link #store} call
     * @param ttl         how long the returned URL should remain valid
     */
    String presignedUrl(String storageKey, Duration ttl);
}
