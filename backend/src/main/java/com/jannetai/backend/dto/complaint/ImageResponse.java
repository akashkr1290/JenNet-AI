package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.enums.ImageType;
import com.jannetai.backend.storage.StorageService;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Still deliberately does NOT expose {@code storageKey} itself -
 * PROJECT_INTEGRATION.md Section 5 ("Media Storage Contract"): it's an
 * internal object key, never a public URL. {@code viewUrl} (Gap-backlog
 * Patch 6, Sep 2026 audit) is the resolved, presigned, time-limited URL a
 * caller who is already authorized to see this image (the only caller is
 * {@code ComplaintService.toResponse}, itself reached only through an
 * already access-controlled complaint lookup) can hand straight to
 * Flutter's image widget - see {@link StorageService#presignedUrl}'s
 * Javadoc for the local-vs-S3 distinction underneath it.
 */
public record ImageResponse(
        Long imageId,
        ImageType imageType,
        String contentType,
        Integer fileSizeBytes,
        LocalDateTime uploadedAt,
        String viewUrl
) {
    /** 15-minute TTL: long enough to load a complaint-detail screen and view its photos, short enough to limit exposure if a link leaks (e.g. via logs, a screenshot, or a shared link). */
    private static final Duration VIEW_URL_TTL = Duration.ofMinutes(15);

    public static ImageResponse from(Image image, StorageService storageService) {
        return new ImageResponse(
                image.getImageId(),
                image.getImageType(),
                image.getContentType(),
                image.getFileSizeBytes(),
                image.getUploadedAt(),
                storageService.presignedUrl(image.getStorageKey(), VIEW_URL_TTL)
        );
    }
}
