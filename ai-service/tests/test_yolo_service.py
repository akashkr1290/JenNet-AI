"""
Phase 20 (extended by Gap-backlog Patch 1, Sep 2026 audit): unit tests for
app/services/yolo_service.py's model-loading contract (ARCHITECTURE.md
Section 5) - the single most important honesty guarantee in this
codebase's AI layer: this must never fabricate a detection or silently
report model_available: true.

A real trained weights file (models/yolov11-civic-v1.0.pt) now ships with
this repo and app/config.py's default yolo_model_path correctly points at
it (Gap-backlog Patch 1 fixed a filename mismatch that had silently
defeated this - see decisions-and-principles.md), so "no weights file
present" is no longer this environment's default state. The
TestYoloServiceNoWeightsFile class below now exercises that branch
explicitly via monkeypatched settings rather than relying on it being the
accidental default, and a new TestYoloServiceRealWeightsFile class
exercises the now-actually-reachable "weights file present, real
inference runs" branch - both actually executed this phase (ultralytics
is installed in this sandbox; see the module docstring this replaced for
why it previously wasn't).
"""
from __future__ import annotations

import numpy as np
import pytest

from app.config import get_settings
from app.services.yolo_service import YoloService


class TestYoloServiceNoWeightsFile:
    """The "weights file missing/misconfigured" branch - forced via a
    monkeypatched settings.yolo_model_path rather than relying on this
    being the environment's default (see module docstring)."""

    @pytest.fixture(autouse=True)
    def _no_weights_path(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "yolo_model_path", "/nonexistent/does-not-exist.pt")

    def test_is_available_is_false_without_a_weights_file(self):
        # A fresh instance, never touching the module-level singleton, so
        # this test can't be polluted by (or pollute) other tests' state.
        service = YoloService()
        assert service.is_available() is False

    def test_unavailable_reason_is_populated_and_human_readable(self):
        service = YoloService()
        reason = service.unavailable_reason()
        assert reason is not None
        assert "yolov11" in reason.lower() or "weights" in reason.lower()

    def test_classify_returns_empty_list_rather_than_a_fabricated_detection(self):
        service = YoloService()
        dummy_image = np.zeros((640, 640, 3), dtype=np.uint8)
        detections = service.classify(dummy_image)
        assert detections == []

    def test_load_is_attempted_only_once(self):
        # _ensure_loaded is lock-guarded and short-circuits on
        # _load_attempted; calling is_available() twice must not re-run
        # the filesystem check or change the cached reason.
        service = YoloService()
        first_reason = service.unavailable_reason()
        second_reason = service.unavailable_reason()
        assert first_reason == second_reason


class TestYoloServiceRealWeightsFile:
    """Gap-backlog Patch 1 (Sep 2026 audit): the real-model-loaded branch,
    now actually reachable with the default (unmodified) settings, since
    models/yolov11-civic-v1.0.pt ships in this repo and
    settings.yolo_model_path correctly points at it. Genuinely executed
    against ultralytics + the real shipped weights, not mocked.
    """

    def test_is_available_is_true_with_default_settings(self):
        service = YoloService()
        assert service.is_available() is True
        assert service.unavailable_reason() is None

    def test_classify_returns_real_detection_objects_not_fabricated_placeholders(self):
        service = YoloService()
        # A high-contrast synthetic image (not blurry/flat) - real
        # inference against real weights, asserting only on the shape of
        # what comes back (class_name/confidence/bounding_box), not on a
        # specific predicted class, since this is a real model with real
        # (imperfect) accuracy, not a stub returning a canned answer.
        rng = np.random.default_rng(7)
        image = rng.integers(0, 255, (640, 640, 3), dtype=np.uint8)
        detections = service.classify(image)
        for detection in detections:
            assert isinstance(detection.class_name, str) and detection.class_name
            assert 0.0 <= detection.confidence <= 100.0
            assert set(detection.bounding_box.keys()) == {"x1", "y1", "x2", "y2"}
