"""
Pilot request (2026-09-28): show the YOLO threshold, and make the decision
path visible - YOLO first; if YOLO is below its threshold, Gemini verifies;
if both fail, the complaint goes to manual review.

These tests pin:
  * YoloService reports its best box that did NOT reach the threshold
    (transparency only - it is never used as a detection);
  * raw_model_output["decision"] names the stage that produced the category,
    and changes nothing about the routing itself.
"""
from __future__ import annotations

import asyncio

import cv2
import numpy as np
import pytest

from app.config import get_settings
from app.schemas.classify import IssueCategory
from app.services import pipeline
from app.services.gemini_service import GeminiResult
from app.services.yolo_service import DetectionResult, YoloService


def _photo_bytes(width: int = 900, height: int = 600) -> bytes:
    rng = np.random.default_rng(5)
    img = rng.integers(60, 200, (height, width, 3)).astype(np.uint8)
    for x in range(0, width, 30):
        cv2.line(img, (x, 0), (x, height), (235, 235, 235), 2)
    return cv2.imencode(".jpg", img)[1].tobytes()


# ------------------------------------------------ YOLO: best box below threshold

class _Tensor(list):
    pass


class _Box:
    def __init__(self, cls, conf, xyxy):
        self.cls, self.conf, self.xyxy = _Tensor([cls]), _Tensor([conf]), [_Xyxy(xyxy)]


class _Xyxy(list):
    def tolist(self):
        return list(self)


class _Result:
    names = {0: "garbage_overflow", 1: "open_manhole", 2: "pothole"}

    def __init__(self, boxes):
        self.boxes = boxes


def _service_with_boxes(boxes):
    class FakeModel:
        def predict(self, **_kw):
            return [_Result(boxes)]

    svc = YoloService()
    svc._model, svc._load_attempted = FakeModel(), True
    return svc


class TestBestBelowThreshold:
    def test_below_threshold_box_is_reported_but_not_detected(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "min_detection_threshold", 50.0)
        svc = _service_with_boxes([_Box(1, 0.40, [1, 2, 3, 4]), _Box(2, 0.22, [5, 6, 7, 8])])
        detections, best = svc.classify_with_best_below(np.zeros((10, 10, 3), np.uint8))
        assert detections == []
        assert best.class_name == "open_manhole" and best.confidence == pytest.approx(40.0)
        assert svc.classify(np.zeros((10, 10, 3), np.uint8)) == []   # unchanged behaviour

    def test_no_best_below_when_a_box_passes(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "min_detection_threshold", 50.0)
        svc = _service_with_boxes([_Box(2, 0.75, [1, 2, 3, 4]), _Box(1, 0.40, [5, 6, 7, 8])])
        detections, best = svc.classify_with_best_below(np.zeros((10, 10, 3), np.uint8))
        assert [d.class_name for d in detections] == ["pothole"] and best is None

    def test_no_boxes_at_all(self):
        detections, best = _service_with_boxes([]).classify_with_best_below(np.zeros((10, 10, 3), np.uint8))
        assert detections == [] and best is None

    def test_no_model_loaded(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "yolo_model_path", "/nonexistent/x.pt")
        assert YoloService().classify_with_best_below(np.zeros((10, 10, 3), np.uint8)) == ([], None)


# ------------------------------------------------------------ decision summary

def _gemini(used=True, agrees=None, category=None):
    return GeminiResult(used=used, description="d" if used else None, agrees_with_top_candidate=agrees,
                        fallback_reason=None if used else "timeout", suggested_category=category)


_TOP = DetectionResult("pothole", 75.0, {"x1": 0, "y1": 0, "x2": 1, "y2": 1})


@pytest.mark.parametrize("available,top,gemini,reason,final,expected", [
    (False, None, _gemini(False), "MODEL_UNAVAILABLE", IssueCategory.GENERAL, "MODEL_UNAVAILABLE"),
    (True, _TOP, _gemini(True, True, "POTHOLE"), "BELOW_AUTO_APPROVE_THRESHOLD", IssueCategory.POTHOLE,
     "YOLO_CONFIRMED_BY_GEMINI"),
    (True, _TOP, _gemini(True, False, "OPEN_MANHOLE"), "GEMINI_REVISED_CATEGORY", IssueCategory.OPEN_MANHOLE,
     "GEMINI_REVISED"),
    (True, _TOP, _gemini(True, False, None), "GEMINI_DISAGREEMENT", IssueCategory.POTHOLE,
     "YOLO_GEMINI_DISAGREED"),
    (True, _TOP, _gemini(False), "BELOW_AUTO_APPROVE_THRESHOLD", IssueCategory.POTHOLE, "YOLO_ONLY"),
    (True, None, _gemini(True, None, "OPEN_MANHOLE"), "GEMINI_CATEGORY_NO_DETECTION", IssueCategory.OPEN_MANHOLE,
     "GEMINI_VERIFIED"),
    (True, None, _gemini(True, None, "GENERAL"), "NO_DETECTION_ABOVE_THRESHOLD", IssueCategory.WATER_LEAKAGE,
     "OCR_HINT"),
    (True, None, _gemini(True, None, "GENERAL"), "NO_DETECTION_ABOVE_THRESHOLD", IssueCategory.GENERAL,
     "MANUAL_REVIEW"),
    (True, None, _gemini(False), "NO_DETECTION_ABOVE_THRESHOLD", IssueCategory.GENERAL, "MANUAL_REVIEW"),
])
def test_decision_outcome(available, top, gemini, reason, final, expected):
    d = pipeline._decision_summary(
        model_available=available, top_detection=top, gemini_result=gemini, routing_reason=reason,
        requires_manual_review=True, final_category=final, auto_approve_threshold=85.0,
        min_detection_threshold=50.0)
    assert d["outcome"] == expected
    assert d["yolo_passed_threshold"] is (top is not None)
    assert d["min_detection_threshold"] == 50.0 and d["auto_approve_threshold"] == 85.0
    assert d["final_category"] == final.value and d["auto_approved"] is False


# ------------------------------------------------------------ end to end

class _BelowYolo:
    def is_available(self):
        return True

    def unavailable_reason(self):
        return None

    def classify_with_best_below(self, image):
        return [], DetectionResult("open_manhole", 40.0, {"x1": 1, "y1": 2, "x2": 3, "y2": 4})


@pytest.mark.parametrize("gemini_category,outcome,category", [
    ("OPEN_MANHOLE", "GEMINI_VERIFIED", IssueCategory.OPEN_MANHOLE),
    ("GENERAL", "MANUAL_REVIEW", IssueCategory.GENERAL),
])
def test_end_to_end_yolo_below_threshold_then_gemini(monkeypatch, gemini_category, outcome, category):
    async def fake_gemini(**_kw):
        return _gemini(True, None, gemini_category)

    monkeypatch.setattr(pipeline, "get_yolo_service", lambda: _BelowYolo())
    monkeypatch.setattr(pipeline.gemini_service, "cross_validate", fake_gemini)
    monkeypatch.setattr(pipeline.ocr_service, "ocr_category_hint", lambda _t: None)
    result = asyncio.run(pipeline.classify(_photo_bytes(), None, None, None))
    yolo = result.raw_model_output["yolo"]
    assert yolo["detections"] == []
    assert yolo["best_below_threshold"]["class_name"] == "open_manhole"
    assert yolo["best_below_threshold"]["confidence"] == 40.0
    assert yolo["min_detection_threshold"] == get_settings().min_detection_threshold
    assert result.raw_model_output["decision"]["outcome"] == outcome
    assert result.category == category
    assert result.requires_manual_review is True and result.confidence == 0.0


def test_services_without_the_detailed_method_still_work(monkeypatch):
    class OldYolo:
        def is_available(self):
            return True

        def unavailable_reason(self):
            return None

        def classify(self, image):
            return [DetectionResult("pothole", 91.0, {"x1": 1, "y1": 2, "x2": 3, "y2": 4})]

    monkeypatch.setattr(pipeline, "get_yolo_service", lambda: OldYolo())
    monkeypatch.setattr(get_settings(), "gemini_api_key", "")
    result = asyncio.run(pipeline.classify(_photo_bytes(), None, None, None))
    assert result.raw_model_output["yolo"]["best_below_threshold"] is None
    assert result.raw_model_output["decision"]["outcome"] == "YOLO_ONLY"
