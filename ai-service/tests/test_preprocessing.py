"""
Unit tests for app/services/preprocessing.py.

PHASE 20 UPDATE: as of Phase 20, this workspace's network egress allowlist
includes pypi.org/files.pythonhosted.org, so pip install and pytest
execution ARE possible here (a change from the Phase 7-19 constraint noted
below, which no longer applies for pip-installable Python packages - see
PROJECT_PROGRESS.md's Phase 20 TESTS section for the full environment-
capability note). These tests were installed (opencv-python-headless,
numpy, pytest) and actually executed this phase; one fixture bug was found
and fixed (see test_flags_dark_image_without_rejecting's inline comment).

Run for real with:

    cd ai-service && pip install -r requirements.txt && pytest
"""
from __future__ import annotations

import numpy as np
import pytest

from app.core.exceptions import UnprocessableImageError
from app.schemas.classify import ImageQualityFlag
from app.services import preprocessing


def _encode_png(image: np.ndarray) -> bytes:
    import cv2

    ok, buf = cv2.imencode(".png", image)
    assert ok
    return buf.tobytes()


def _solid_image(width: int, height: int, value: int = 200) -> np.ndarray:
    """A flat-color image: NOT blurry-by-Laplacian-variance-zero, but also
    not a real photo. Used for size/darkness assertions where texture
    doesn't matter."""
    return np.full((height, width, 3), value, dtype=np.uint8)


def _textured_image(width: int, height: int) -> np.ndarray:
    """Checkerboard pattern - has real edges, so blur-variance is high
    (ACCEPTABLE), unlike a flat solid-color image."""
    image = np.zeros((height, width, 3), dtype=np.uint8)
    block = 8
    for y in range(0, height, block):
        for x in range(0, width, block):
            if ((x // block) + (y // block)) % 2 == 0:
                image[y : y + block, x : x + block] = 220
    return image


class TestQualityGate:
    def test_rejects_too_small_image(self):
        tiny = _textured_image(50, 50)
        with pytest.raises(UnprocessableImageError) as exc_info:
            preprocessing.preprocess_image(_encode_png(tiny))
        assert exc_info.value.details["quality_flag"] == ImageQualityFlag.TOO_SMALL.value

    def test_rejects_blurry_flat_image(self):
        # A flat solid-color image has ~zero Laplacian variance -> BLURRY,
        # even though it clears the minimum size gate.
        flat = _solid_image(640, 640, value=200)
        with pytest.raises(UnprocessableImageError) as exc_info:
            preprocessing.preprocess_image(_encode_png(flat))
        assert exc_info.value.details["quality_flag"] == ImageQualityFlag.BLURRY.value

    def test_accepts_textured_well_lit_image(self):
        good = _textured_image(640, 640)
        result = preprocessing.preprocess_image(_encode_png(good))
        assert result.quality_flag == ImageQualityFlag.ACCEPTABLE
        assert result.width == 640
        assert result.height == 640

    def test_flags_dark_image_without_rejecting(self):
        # Dark but still textured -> TOO_DARK flag, NOT a rejection (see
        # preprocessing.py module docstring for why TOO_DARK doesn't raise).
        #
        # PHASE 20 FIX: this fixture previously scaled brightness by 0.05,
        # which also crushes edge contrast (Laplacian variance scales with
        # the square of the scale factor), pushing the image's blur-variance
        # below blur_variance_threshold (80.0) BEFORE the darkness check
        # ever runs - the quality gate correctly flags BLURRY first (checked
        # ahead of TOO_DARK in _assess_quality's if/elif chain) and this
        # test failed with UnprocessableImageError instead of asserting
        # TOO_DARK. This was a test-fixture bug, not a preprocessing.py
        # logic bug: production's blur-before-darkness precedence is
        # intentional (a photo that's both blurry AND dark should be
        # rejected as BLURRY, the more actionable citizen-facing message).
        # 0.3 was picked empirically: mean brightness ~33 (< the 35.0
        # _DARKNESS_MEAN_THRESHOLD) while blur-variance stays ~2680 (well
        # above the 80.0 threshold), so this fixture now actually exercises
        # the TOO_DARK-without-BLURRY path it claims to test. See
        # PROJECT_INTEGRATION.md Section 6, Phase 20 entry.
        dark = _textured_image(640, 640)
        dark = (dark.astype(np.float32) * 0.3).astype(np.uint8)  # crush brightness, keep contrast
        result = preprocessing.preprocess_image(_encode_png(dark))
        assert result.quality_flag == ImageQualityFlag.TOO_DARK

    def test_rejects_undecodable_bytes(self):
        with pytest.raises(UnprocessableImageError):
            preprocessing.preprocess_image(b"not an image")

    def test_normalized_output_matches_model_input_size(self):
        from app.services.yolo_service import MODEL_INPUT_SIZE

        good = _textured_image(1024, 768)
        result = preprocessing.preprocess_image(_encode_png(good))
        h, w = result.normalized_image.shape[:2]
        assert (w, h) == MODEL_INPUT_SIZE
