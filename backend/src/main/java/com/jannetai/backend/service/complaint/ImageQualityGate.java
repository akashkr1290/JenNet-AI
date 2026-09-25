package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.ai.AiQualityResult;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.exception.ImageQualityRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Audit GAP-032 (SRS 21.3: an unusable photo is "rejected at intake with a
 * citizen-facing prompt to retake the photo, before any model inference";
 * SRS 17.2: minimum 480p). Runs before ComplaintService.create, so a rejected
 * photo never becomes a complaint. Previously the rejection came from the AI
 * pipeline AFTER the complaint was stored, and the citizen was never told.
 *
 * <ol>
 *   <li>Short side below {@code app.photo.min-short-side-px} (480) - checked
 *       locally, works even when ai-service is down;</li>
 *   <li>ai-service {@code /quality} (blur) when the AI integration is enabled.
 *       If ai-service cannot answer, the photo is ACCEPTED: an AI outage must
 *       not stop citizens from reporting - the asynchronous pipeline and the
 *       Verification Team still review it.</li>
 * </ol>
 * Bytes that are not a decodable image are left to ComplaintService's own
 * validation, which rejects them with its existing messages.
 */
@Component
public class ImageQualityGate {

    private static final Logger log = LoggerFactory.getLogger(ImageQualityGate.class);

    private final AiServiceClient aiServiceClient;
    private final AiServiceProperties aiServiceProperties;

    @Value("${app.photo.min-short-side-px:480}")
    private int minShortSidePx = 480;

    @Value("${app.photo.quality-gate-enabled:true}")
    private boolean aiQualityGateEnabled = true;

    public ImageQualityGate(AiServiceClient aiServiceClient, AiServiceProperties aiServiceProperties) {
        this.aiServiceClient = aiServiceClient;
        this.aiServiceProperties = aiServiceProperties;
    }

    public void check(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return; // ComplaintService reports the missing photo
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            return; // ComplaintService reports the unreadable upload
        }

        int[] size = dimensions(bytes);
        if (size != null && Math.min(size[0], size[1]) < minShortSidePx) {
            throw new ImageQualityRejectedException("TOO_SMALL",
                    "The photo resolution is too low (" + size[0] + "x" + size[1] + ", minimum " + minShortSidePx
                            + "p). Please retake it with the camera.");
        }

        if (!aiQualityGateEnabled || !aiServiceProperties.isEnabled()) {
            return;
        }
        AiQualityResult result;
        try {
            result = aiServiceClient.checkQuality(Base64.getEncoder().encodeToString(bytes));
        } catch (AiServiceCallException e) {
            log.warn("Photo quality check unavailable [{}] - accepting the photo; AI review follows asynchronously",
                    e.getErrorCode());
            return;
        }
        if (!result.acceptable()) {
            throw new ImageQualityRejectedException(result.qualityFlag(),
                    result.message() != null ? result.message() : "The photo cannot be used. Please retake it.");
        }
    }

    /** {width, height} for JPEG/PNG, null when ImageIO cannot decode it (e.g. WEBP). */
    static int[] dimensions(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
