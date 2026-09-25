package com.jannetai.backend.exception;

/**
 * Audit GAP-032 (SRS 21.3): the photo is unusable (blurry / below 480p); the
 * citizen is asked to retake it and NO complaint is created. Mapped to 422
 * IMAGE_QUALITY_REJECTED with the citizen-facing message.
 */
public class ImageQualityRejectedException extends RuntimeException {

    private final String qualityFlag;

    public ImageQualityRejectedException(String qualityFlag, String message) {
        super(message);
        this.qualityFlag = qualityFlag;
    }

    public String getQualityFlag() {
        return qualityFlag;
    }
}
