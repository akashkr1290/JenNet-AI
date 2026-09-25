"""
Image pre-processing and normalization (SRS 15.4 Features, 21.3).

Validates and normalizes an uploaded image prior to model inference:
resizing, denoising, contrast adjustment, and blur/quality detection.
Images flagged BLURRY or TOO_SMALL are rejected at intake (raises
UnprocessableImageError) before any model inference is attempted, per SRS
21.3 Fallback Logic. TOO_DARK is flagged but NOT rejected - the SRS lists
it as a quality flag value but only names Blurry/Too Small in the reject
fallback sentence, so TOO_DARK is treated as a confidence-ceiling signal
instead (mirrors how the quality flag generally works per 21.3's
Confidence Score note: "a poor quality flag reduces the ceiling on overall
classification confidence"). Recorded in PROJECT_INTEGRATION.md Section 6.
"""
from __future__ import annotations

import cv2
import numpy as np

from app.config import get_settings
from app.core.exceptions import UnprocessableImageError
from app.schemas.classify import ImageQualityFlag

# Quality flags that block inference entirely (SRS 21.3 Fallback Logic).
_REJECTING_FLAGS = {ImageQualityFlag.BLURRY, ImageQualityFlag.TOO_SMALL}

# Darkness heuristic: mean pixel brightness (0-255) below this is TOO_DARK.
_DARKNESS_MEAN_THRESHOLD = 35.0


class PreprocessResult:
    __slots__ = ("normalized_image", "quality_flag", "width", "height", "blur_variance")

    def __init__(
        self,
        normalized_image: np.ndarray,
        quality_flag: ImageQualityFlag,
        width: int,
        height: int,
        blur_variance: float,
    ) -> None:
        self.normalized_image = normalized_image
        self.quality_flag = quality_flag
        self.width = width
        self.height = height
        self.blur_variance = blur_variance


def preprocess_image(image_bytes: bytes) -> PreprocessResult:
    settings = get_settings()

    array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        raise UnprocessableImageError(
            "Image bytes could not be decoded - not a valid JPEG/PNG/WEBP image."
        )

    height, width = image.shape[:2]
    quality_flag = _assess_quality(image, width, height, settings)

    if quality_flag in _REJECTING_FLAGS:
        raise UnprocessableImageError(
            f"Image rejected at intake: {quality_flag.value}.",
            details={
                "quality_flag": quality_flag.value,
                "width": width,
                "height": height,
                "min_width_px": settings.min_image_width_px,
                "min_image_height_px": settings.min_image_height_px,
            },
        )

    normalized = _normalize(image, settings.preprocess_max_side_px)
    blur_variance = _laplacian_variance(image)
    return PreprocessResult(
        normalized_image=normalized,
        quality_flag=quality_flag,
        width=width,
        height=height,
        blur_variance=blur_variance,
    )


class QualityReport:
    """Audit GAP-032: result of the intake quality check alone (no inference)."""
    __slots__ = ("acceptable", "quality_flag", "width", "height", "blur_variance")

    def __init__(self, acceptable: bool, quality_flag: ImageQualityFlag, width: int, height: int,
                 blur_variance: float) -> None:
        self.acceptable = acceptable
        self.quality_flag = quality_flag
        self.width = width
        self.height = height
        self.blur_variance = blur_variance


def assess_image_quality(image_bytes: bytes) -> QualityReport:
    """
    Audit GAP-032 (SRS 21.3: "rejected at intake with a citizen-facing prompt
    to retake the photo, before any model inference"): the same checks
    preprocess_image applies, without denoising or inference, so the backend
    can ask BEFORE it stores the complaint. Raises UnprocessableImageError only
    for bytes that are not an image at all.
    """
    settings = get_settings()
    array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        raise UnprocessableImageError(
            "Image bytes could not be decoded - not a valid JPEG/PNG/WEBP image."
        )
    height, width = image.shape[:2]
    flag = _assess_quality(image, width, height, settings)
    return QualityReport(
        acceptable=flag not in _REJECTING_FLAGS,
        quality_flag=flag,
        width=width,
        height=height,
        blur_variance=_laplacian_variance(image),
    )


def _assess_quality(image: np.ndarray, width: int, height: int, settings) -> ImageQualityFlag:
    if width < settings.min_image_width_px or height < settings.min_image_height_px:
        return ImageQualityFlag.TOO_SMALL

    blur_variance = _laplacian_variance(image)
    if blur_variance < settings.blur_variance_threshold:
        return ImageQualityFlag.BLURRY

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    mean_brightness = float(np.mean(gray))
    if mean_brightness < _DARKNESS_MEAN_THRESHOLD:
        return ImageQualityFlag.TOO_DARK

    return ImageQualityFlag.ACCEPTABLE


def _laplacian_variance(image: np.ndarray) -> float:
    """Standard variance-of-Laplacian blur metric - lower means blurrier."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def downscale_to_max_side(image: np.ndarray, max_side: int) -> np.ndarray:
    """Audit GAP-009: shrink (never enlarge) so the longer side is <= max_side."""
    height, width = image.shape[:2]
    longest = max(height, width)
    if max_side <= 0 or longest <= max_side:
        return image
    scale = max_side / float(longest)
    new_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return cv2.resize(image, new_size, interpolation=cv2.INTER_AREA)


def _normalize(image: np.ndarray, max_side_px: int = 1600) -> np.ndarray:
    """
    Denoise + contrast-adjust + resize to the fixed model input size.
    Resize target matches the YOLO service's expected input resolution -
    see services/yolo_service.py MODEL_INPUT_SIZE.

    Audit GAP-009: non-local-means denoising is O(pixels); it used to run on
    the full-resolution upload (24.8 s of 25.4 s for a 4000x3000 photo on the
    audit host). The image is now first reduced to at most max_side_px on its
    longer side - still 2.5x the 640 px model input, so the final INTER_AREA
    resize sees the same detail.
    """
    from app.services.yolo_service import MODEL_INPUT_SIZE

    working = downscale_to_max_side(image, max_side_px)
    denoised = cv2.fastNlMeansDenoisingColored(working, None, 6, 6, 7, 21)

    lab = cv2.cvtColor(denoised, cv2.COLOR_BGR2LAB)
    l_channel, a_channel, b_channel = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_equalized = clahe.apply(l_channel)
    contrast_adjusted = cv2.cvtColor(
        cv2.merge((l_equalized, a_channel, b_channel)), cv2.COLOR_LAB2BGR
    )

    resized = cv2.resize(contrast_adjusted, MODEL_INPUT_SIZE, interpolation=cv2.INTER_AREA)
    return resized
