"""
Live pilot finding (2026-09-27): clear photos of a pothole and a broken
street light came back as GENERAL / confidence 0 because YOLOv11 put nothing
above min_detection_threshold, and Gemini was then never asked. Also, YOLO
was fed the denoised, contrast-boosted image STRETCHED to 640x640 instead of
the photo it was trained on (Ultralytics letterbox keeps proportions).

These tests pin the fix: YOLO sees the undistorted photo, and when it finds
nothing Gemini is asked for the category - which is only ever a suggestion
for the Verification Team, never an auto-approval.
"""
from __future__ import annotations

import asyncio

import cv2
import numpy as np
import pytest

from app.config import get_settings
from app.schemas.classify import IssueCategory
from app.services import gemini_service as gs
from app.services import pipeline, preprocessing
from app.services.gemini_service import GeminiResult
from app.services.yolo_service import DetectionResult, YoloService


def _photo_bytes(width: int = 900, height: int = 600) -> bytes:
    rng = np.random.default_rng(3)
    img = rng.integers(60, 200, (height, width, 3)).astype(np.uint8)
    for x in range(0, width, 30):
        cv2.line(img, (x, 0), (x, height), (235, 235, 235), 2)
    return cv2.imencode(".jpg", img)[1].tobytes()


# ------------------------------------------------------------ what YOLO sees

class TestModelImage:
    def test_model_image_keeps_the_photos_proportions(self):
        result = preprocessing.preprocess_image(_photo_bytes(900, 600))
        assert result.model_image.shape[:2] == (600, 900)          # untouched, not squashed
        assert result.normalized_image.shape[:2] == (640, 640)     # still produced, for OCR

    def test_large_photos_are_only_shrunk_with_proportions_kept(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "preprocess_max_side_px", 450)
        result = preprocessing.preprocess_image(_photo_bytes(900, 600))
        assert result.model_image.shape[:2] == (300, 450)

    def test_yolo_is_asked_to_letterbox_to_the_training_size(self):
        seen = {}

        class FakeModel:
            def predict(self, **kwargs):
                seen.update(kwargs)
                return []

        svc = YoloService()
        svc._model, svc._load_attempted = FakeModel(), True
        image = np.zeros((600, 900, 3), np.uint8)
        assert svc.classify(image) == []
        assert seen["source"] is image and seen["imgsz"] == 640 and seen["conf"] == 0.0

    def test_pipeline_feeds_yolo_the_undistorted_photo_and_records_its_size(self, monkeypatch):
        fed = {}

        class FakeYolo:
            def is_available(self):
                return True

            def unavailable_reason(self):
                return None

            def classify(self, image):
                fed["shape"] = image.shape
                return [DetectionResult("pothole", 91.0, {"x1": 90, "y1": 60, "x2": 450, "y2": 300})]

        monkeypatch.setattr(pipeline, "get_yolo_service", lambda: FakeYolo())
        monkeypatch.setattr(get_settings(), "gemini_api_key", "")
        result = asyncio.run(pipeline.classify(_photo_bytes(900, 600), None, None, None))
        assert fed["shape"][:2] == (600, 900)
        # Boxes are in the photo's own pixels, so the backend can normalise them.
        assert result.raw_model_output["yolo"]["model_input_size"] == [900, 600]
        assert result.category == IssueCategory.POTHOLE


# ------------------------------------------------ Gemini when YOLO sees nothing

class TestGeminiWhenYoloFindsNothing:
    def test_gemini_is_called_even_without_a_yolo_candidate(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "gemini_api_key", "test-key-not-real")
        seen = {}

        async def fake_call(image_bytes, top, candidates, description):
            seen["top"], seen["prompt"] = top, gs._build_prompt(top, candidates, description)
            return GeminiResult(used=True, description="A deep pothole filled with water.",
                                agrees_with_top_candidate=None, fallback_reason=None,
                                suggested_category="POTHOLE")

        monkeypatch.setattr(gs, "_call_gemini", fake_call)
        result = asyncio.run(gs.cross_validate(b"img", None, [], "big hole on main road"))
        assert result.used is True and result.suggested_category == "POTHOLE"
        assert seen["top"] is None
        assert "did not recognise" in seen["prompt"] and "big hole on main road" in seen["prompt"]
        assert "top candidate" not in seen["prompt"]

    def test_no_key_still_means_no_call(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "gemini_api_key", "")
        result = asyncio.run(gs.cross_validate(b"img", None, [], None))
        assert result.used is False and result.fallback_reason == "GEMINI_API_KEY not configured"

    def test_classify_prompt_only_offers_supported_categories(self):
        prompt = gs._build_prompt(None, [], None)
        for category in gs.SUPPORTED_CATEGORIES:
            assert category in prompt
        assert '"category"' in prompt

    def test_gemini_category_becomes_the_suggestion_and_always_needs_review(self):
        gemini = GeminiResult(used=True, description="d", agrees_with_top_candidate=None, fallback_reason=None,
                              suggested_category="BROKEN_STREET_LIGHT")
        result = pipeline._apply_category_revisions(
            yolo_category=IssueCategory.GENERAL, top_detection=None, gemini_result=gemini,
            gemini_category=IssueCategory.BROKEN_STREET_LIGHT, ocr_hint=IssueCategory.WATER_LEAKAGE,
            routing_reason="NO_DETECTION_ABOVE_THRESHOLD", requires_manual_review=True)
        assert result == (IssueCategory.BROKEN_STREET_LIGHT, "GEMINI_CATEGORY_NO_DETECTION", True)

    def test_gemini_general_does_not_override_the_ocr_hint(self):
        gemini = GeminiResult(used=True, description="d", agrees_with_top_candidate=None, fallback_reason=None,
                              suggested_category="GENERAL")
        category, _reason, review = pipeline._apply_category_revisions(
            yolo_category=IssueCategory.GENERAL, top_detection=None, gemini_result=gemini,
            gemini_category=IssueCategory.GENERAL, ocr_hint=IssueCategory.OPEN_MANHOLE,
            routing_reason="NO_DETECTION_ABOVE_THRESHOLD", requires_manual_review=True)
        assert category == IssueCategory.OPEN_MANHOLE and review is True

    @pytest.mark.parametrize("gemini_category", ["POTHOLE", "GENERAL", None])
    def test_end_to_end_no_detection_is_never_auto_approved(self, monkeypatch, gemini_category):
        class EmptyYolo:
            def is_available(self):
                return True

            def unavailable_reason(self):
                return None

            def classify(self, image):
                return []

        async def fake_gemini(**_kw):
            return GeminiResult(used=True, description="desc", agrees_with_top_candidate=None,
                                fallback_reason=None, suggested_category=gemini_category)

        monkeypatch.setattr(pipeline, "get_yolo_service", lambda: EmptyYolo())
        monkeypatch.setattr(pipeline.gemini_service, "cross_validate", fake_gemini)
        result = asyncio.run(pipeline.classify(_photo_bytes(), None, None, None))
        assert result.requires_manual_review is True
        assert result.confidence == 0.0          # no model confidence is invented
        if gemini_category == "POTHOLE":
            assert result.category == IssueCategory.POTHOLE
            assert result.routing_reason == "GEMINI_CATEGORY_NO_DETECTION"
            assert result.raw_model_output["gemini"]["suggested_category"] == "POTHOLE"
        else:
            assert result.routing_reason == "NO_DETECTION_ABOVE_THRESHOLD"
