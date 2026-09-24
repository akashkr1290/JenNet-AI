"""
Unit tests for the confidence-scoring/routing logic in
app/services/pipeline.py (SRS 15.4 Business Rules).

NOT VERIFIED (not executed) - see tests/test_preprocessing.py docstring
for why. These tests exercise `_score_and_route` directly with synthetic
inputs so they don't require a real YOLOv11 model, Gemini API key, or
network access - only app.config's default thresholds.
"""
from __future__ import annotations

from types import SimpleNamespace

from app.config import get_settings
from app.services.pipeline import _score_and_route
from app.services.yolo_service import DetectionResult


def _detection(confidence: float, class_name: str = "pothole") -> DetectionResult:
    return DetectionResult(
        class_name=class_name,
        confidence=confidence,
        bounding_box={"x1": 0, "y1": 0, "x2": 10, "y2": 10},
    )


def _gemini_result(used: bool, agrees=None, reason=None):
    return SimpleNamespace(used=used, agrees_with_top_candidate=agrees, fallback_reason=reason)


class TestScoreAndRoute:
    def setup_method(self):
        self.settings = get_settings()

    def test_model_unavailable_routes_to_manual_review(self):
        confidence, reason, requires_review = _score_and_route(
            model_available=False,
            top_detection=None,
            gemini_result=_gemini_result(False, reason="n/a"),
            settings=self.settings,
        )
        assert confidence == 0.0
        assert reason == "MODEL_UNAVAILABLE"
        assert requires_review is True

    def test_no_detection_above_threshold_routes_to_manual_review(self):
        confidence, reason, requires_review = _score_and_route(
            model_available=True,
            top_detection=None,
            gemini_result=_gemini_result(False, reason="no candidate"),
            settings=self.settings,
        )
        assert confidence == 0.0
        assert reason == "NO_DETECTION_ABOVE_THRESHOLD"
        assert requires_review is True

    def test_high_confidence_with_gemini_agreement_auto_approves(self):
        detection = _detection(confidence=90.0)
        confidence, reason, requires_review = _score_and_route(
            model_available=True,
            top_detection=detection,
            gemini_result=_gemini_result(True, agrees=True),
            settings=self.settings,
        )
        assert confidence == min(100.0, 90.0 + 8.0)
        assert reason == "AUTO_APPROVED"
        assert requires_review is False

    def test_gemini_disagreement_forces_manual_review(self):
        # Even a high YOLO confidence should be knocked below threshold by
        # the disagreement penalty and routed for manual review.
        detection = _detection(confidence=90.0)
        confidence, reason, requires_review = _score_and_route(
            model_available=True,
            top_detection=detection,
            gemini_result=_gemini_result(True, agrees=False),
            settings=self.settings,
        )
        assert confidence == max(0.0, 90.0 - 25.0)
        assert reason == "GEMINI_DISAGREEMENT"
        assert requires_review is True

    def test_gemini_fallback_caps_confidence(self):
        # A YOLO confidence above the fallback cap should be capped down
        # when Gemini wasn't used, potentially forcing manual review.
        detection = _detection(confidence=95.0)
        confidence, reason, requires_review = _score_and_route(
            model_available=True,
            top_detection=detection,
            gemini_result=_gemini_result(False, reason="GEMINI_API_KEY not configured"),
            settings=self.settings,
        )
        assert confidence == self.settings.gemini_fallback_confidence_cap
        assert requires_review is True  # cap (70) is below default threshold (85)
        assert reason == "BELOW_AUTO_APPROVE_THRESHOLD"

    def test_low_confidence_without_gemini_routes_to_manual_review(self):
        detection = _detection(confidence=40.0)
        confidence, reason, requires_review = _score_and_route(
            model_available=True,
            top_detection=detection,
            gemini_result=_gemini_result(False, reason="GEMINI_API_KEY not configured"),
            settings=self.settings,
        )
        assert confidence == 40.0
        assert reason == "BELOW_AUTO_APPROVE_THRESHOLD"
        assert requires_review is True


class TestCategoryMapping:
    def test_known_class_maps_to_matching_category(self):
        from app.services.pipeline import _map_category
        from app.schemas.classify import IssueCategory

        assert _map_category("pothole") == IssueCategory.POTHOLE
        assert _map_category("garbage_overflow") == IssueCategory.GARBAGE_OVERFLOW

    def test_unknown_or_missing_class_maps_to_general(self):
        from app.services.pipeline import _map_category
        from app.schemas.classify import IssueCategory

        assert _map_category(None) == IssueCategory.GENERAL
        assert _map_category("some_unrecognized_class") == IssueCategory.GENERAL
