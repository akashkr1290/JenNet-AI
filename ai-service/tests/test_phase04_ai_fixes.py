"""
Audit Phase 04 fixes (Sep 2026 fix session): GAP-007, 009, 011, 032, 034, 053.

Everything here runs locally with the real code paths. Gemini is exercised
against the REAL google-genai SDK types with only the network client replaced
by a stub - no request leaves the machine and no Gemini result is invented
by the code under test (the stub's reply is the test's own input).
"""
from __future__ import annotations

import asyncio
import base64
import time

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.api.routes.duplicate_check import effective_duplicate_settings
from app.config import get_settings
from app.main import app
from app.schemas.classify import IssueCategory
from app.services import gemini_service as gs
from app.services import ocr_service, pipeline, preprocessing
from app.services.gemini_service import GeminiResult
from app.services.yolo_service import DetectionResult

AUTH = {"X-Internal-Api-Key": get_settings().ai_service_api_key}
client = TestClient(app)


def _checkerboard(width: int, height: int, block: int = 8) -> np.ndarray:
    image = np.zeros((height, width, 3), dtype=np.uint8)
    for y in range(0, height, block):
        for x in range(0, width, block):
            if ((x // block) + (y // block)) % 2 == 0:
                image[y:y + block, x:x + block] = 220
    return image


def _png(image: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", image)
    assert ok
    return buf.tobytes()


def _b64(image: np.ndarray) -> str:
    return base64.b64encode(_png(image)).decode()


# ---------------------------------------------------------------- GAP-009

class TestDownscaleBeforeDenoise:
    def test_large_image_is_reduced_to_the_max_side_keeping_aspect_ratio(self):
        out = preprocessing.downscale_to_max_side(np.zeros((3000, 4000, 3), np.uint8), 1600)
        assert out.shape[:2] == (1200, 1600)

    def test_small_image_is_never_enlarged(self):
        img = np.zeros((600, 800, 3), np.uint8)
        assert preprocessing.downscale_to_max_side(img, 1600) is img

    def test_normalize_output_is_still_the_model_input_size(self):
        from app.services.yolo_service import MODEL_INPUT_SIZE
        out = preprocessing._normalize(_checkerboard(4000, 3000), 1600)
        assert (out.shape[1], out.shape[0]) == tuple(MODEL_INPUT_SIZE)

    def test_downscaling_makes_12mp_normalisation_much_faster(self):
        img = _checkerboard(4000, 3000)
        t0 = time.monotonic(); preprocessing._normalize(img, 0); full = time.monotonic() - t0
        t0 = time.monotonic(); preprocessing._normalize(img, 1600); reduced = time.monotonic() - t0
        assert reduced < full / 2, (full, reduced)


# ---------------------------------------------------------------- GAP-032

class TestIntakeQualityGate:
    def test_480p_is_the_minimum_short_side(self):
        s = get_settings()
        assert s.min_image_width_px == 480 and s.min_image_height_px == 480

    def test_640x480_sharp_photo_is_acceptable_in_either_orientation(self):
        assert preprocessing.assess_image_quality(_png(_checkerboard(640, 480))).acceptable
        assert preprocessing.assess_image_quality(_png(_checkerboard(480, 640))).acceptable

    def test_below_480p_is_rejected_as_too_small(self):
        report = preprocessing.assess_image_quality(_png(_checkerboard(640, 400)))
        assert not report.acceptable and report.quality_flag.value == "TOO_SMALL"

    def test_blurry_photo_is_rejected(self):
        blurred = cv2.GaussianBlur(_checkerboard(800, 600, block=64), (51, 51), 30)
        report = preprocessing.assess_image_quality(_png(blurred))
        assert not report.acceptable and report.quality_flag.value == "BLURRY"

    def test_quality_endpoint_returns_a_retake_prompt(self):
        r = client.post("/api/v1/ai/quality", json={"image_base64": _b64(_checkerboard(300, 300))}, headers=AUTH)
        assert r.status_code == 200
        body = r.json()
        assert body["acceptable"] is False and body["quality_flag"] == "TOO_SMALL"
        assert "retake" in body["message"].lower()

    def test_quality_endpoint_accepts_a_good_photo(self):
        r = client.post("/api/v1/ai/quality", json={"image_base64": _b64(_checkerboard(800, 600))}, headers=AUTH)
        assert r.status_code == 200 and r.json()["acceptable"] is True and r.json()["message"] is None

    def test_quality_endpoint_requires_the_internal_key(self):
        r = client.post("/api/v1/ai/quality", json={"image_base64": _b64(_checkerboard(800, 600))})
        assert r.status_code == 401


# ---------------------------------------------------------------- GAP-011

def _detection(name: str, confidence: float) -> DetectionResult:
    return DetectionResult(class_name=name, confidence=confidence, bounding_box={})


class TestConfigurableThresholds:
    def test_category_rule_beats_platform_setting_beats_env_default(self):
        s = get_settings()
        assert pipeline.resolve_confidence_threshold("POTHOLE", 70, {"POTHOLE": 60}, s) == 60
        assert pipeline.resolve_confidence_threshold("GARBAGE_OVERFLOW", 70, {"POTHOLE": 60}, s) == 70
        assert pipeline.resolve_confidence_threshold("POTHOLE", None, None, s) == s.auto_approve_confidence_threshold

    def test_lower_admin_threshold_auto_approves_what_the_default_would_not(self):
        gemini = GeminiResult(used=True, description=None, agrees_with_top_candidate=None, fallback_reason=None)
        s = get_settings()
        det = _detection("pothole", 80.0)
        _, reason_default, review_default = pipeline._score_and_route(
            model_available=True, top_detection=det, gemini_result=gemini, settings=s)
        _, reason_admin, review_admin = pipeline._score_and_route(
            model_available=True, top_detection=det, gemini_result=gemini, settings=s, threshold=75.0)
        assert review_default is True and reason_default == "BELOW_AUTO_APPROVE_THRESHOLD"
        assert review_admin is False and reason_admin == "AUTO_APPROVED"

    def test_duplicate_auto_merge_threshold_is_applied_per_request(self):
        s = get_settings()
        eff = effective_duplicate_settings(s, 70.0)
        assert eff.duplicate_auto_merge_similarity_threshold == 70.0
        assert eff.duplicate_manual_review_similarity_threshold <= 70.0
        assert eff.duplicate_no_gps_similarity_threshold >= 90.0   # SRS 21.5 floor kept
        assert s.duplicate_auto_merge_similarity_threshold == 80.0  # global settings untouched
        assert effective_duplicate_settings(s, None) is s

    def test_classify_request_rejects_out_of_range_thresholds(self):
        r = client.post("/api/v1/ai/classify",
                        json={"image_base64": _b64(_checkerboard(640, 480)), "confidence_threshold": 10},
                        headers=AUTH)
        assert r.status_code == 422


# ---------------------------------------------------------------- GAP-053

class TestRevisedCategoryAndOcrHint:
    def test_gemini_reply_category_is_parsed_only_when_supported(self):
        assert gs.parse_gemini_reply_full('{"agrees": false, "category": "water_leakage", "description": "x"}') == (
            False, "x", "WATER_LEAKAGE")
        assert gs.parse_gemini_reply_full('{"agrees": false, "category": "flooding", "description": "x"}')[2] is None

    def test_disagreement_with_a_concrete_category_revises_it_and_forces_manual_review(self):
        gemini = GeminiResult(used=True, description="d", agrees_with_top_candidate=False, fallback_reason=None,
                              suggested_category="WATER_LEAKAGE")
        category, reason, review = pipeline._apply_category_revisions(
            yolo_category=IssueCategory.POTHOLE, top_detection=_detection("pothole", 90), gemini_result=gemini,
            gemini_category=IssueCategory.WATER_LEAKAGE, ocr_hint=None,
            routing_reason="GEMINI_DISAGREEMENT", requires_manual_review=True)
        assert category == IssueCategory.WATER_LEAKAGE and reason == "GEMINI_REVISED_CATEGORY" and review is True

    def test_agreement_keeps_the_yolo_category_and_decision(self):
        gemini = GeminiResult(used=True, description="d", agrees_with_top_candidate=True, fallback_reason=None,
                              suggested_category="POTHOLE")
        result = pipeline._apply_category_revisions(
            yolo_category=IssueCategory.POTHOLE, top_detection=_detection("pothole", 90), gemini_result=gemini,
            gemini_category=IssueCategory.POTHOLE, ocr_hint=None, routing_reason="AUTO_APPROVED",
            requires_manual_review=False)
        assert result == (IssueCategory.POTHOLE, "AUTO_APPROVED", False)

    def test_ocr_hint_fills_the_category_only_when_yolo_found_nothing(self):
        gemini = GeminiResult(used=False, description=None, agrees_with_top_candidate=None, fallback_reason="x")
        category, reason, review = pipeline._apply_category_revisions(
            yolo_category=IssueCategory.GENERAL, top_detection=None, gemini_result=gemini, gemini_category=None,
            ocr_hint=IssueCategory.OPEN_MANHOLE, routing_reason="NO_DETECTION_ABOVE_THRESHOLD",
            requires_manual_review=True)
        assert category == IssueCategory.OPEN_MANHOLE and review is True

    @pytest.mark.parametrize("text,expected", [
        ("DANGER: OPEN MANHOLE - sewer work in progress", "OPEN_MANHOLE"),
        ("Delhi Jal Board pipeline repair", "WATER_LEAKAGE"),
        ("कचरा यहाँ न फेंकें", "GARBAGE_OVERFLOW"),
        ("Happy Diwali", None),
        (None, None),
    ])
    def test_ocr_keywords(self, text, expected):
        assert ocr_service.ocr_category_hint(text) == expected


# ---------------------------------------------------------------- GAP-007

class _FakeResponse:
    def __init__(self, text):
        self.text = text


class _FakeModels:
    def __init__(self, calls):
        self.calls = calls

    async def generate_content(self, *, model, contents, config=None):
        self.calls.append({"model": model, "contents": contents, "config": config})
        return _FakeResponse('{"agrees": false, "category": "GARBAGE_OVERFLOW", "description": "Overflowing bin."}')


class TestGoogleGenaiSdk:
    def test_call_uses_the_supported_sdk_configured_model_and_bounded_timeout(self, monkeypatch):
        from google import genai
        from google.genai import types

        calls, clients = [], []

        class FakeClient:
            def __init__(self, *, api_key, http_options):
                clients.append({"api_key": api_key, "http_options": http_options})
                self.aio = type("Aio", (), {"models": _FakeModels(calls)})()

        s = get_settings()
        monkeypatch.setattr(s, "gemini_api_key", "test-key-not-real")
        monkeypatch.setattr(s, "gemini_timeout_seconds", 8.0)
        monkeypatch.setattr(genai, "Client", FakeClient)

        result = asyncio.run(gs._call_gemini(_png(_checkerboard(64, 64)), "POTHOLE", ["POTHOLE"], None))

        assert result.used is True and result.agrees_with_top_candidate is False
        assert result.suggested_category == "GARBAGE_OVERFLOW"
        assert calls[0]["model"] == s.gemini_model_name
        assert isinstance(clients[0]["http_options"], types.HttpOptions)
        assert clients[0]["http_options"].timeout == 8000          # milliseconds
        image_part = calls[0]["contents"][0]
        assert isinstance(image_part, types.Part) and image_part.inline_data.mime_type == "image/png"
        assert calls[0]["config"].response_mime_type == "application/json"

    def test_default_model_is_not_the_retired_gemini_15(self):
        assert get_settings().gemini_model_name != "gemini-1.5-flash"

    def test_old_sdk_is_not_imported_anywhere(self):
        import pathlib
        root = pathlib.Path(__file__).resolve().parents[1] / "app"
        offenders = [p for p in root.rglob("*.py") if "google.generativeai" in p.read_text()]
        assert offenders == []

    def test_missing_key_still_degrades_to_manual_review(self, monkeypatch):
        monkeypatch.setattr(get_settings(), "gemini_api_key", "")
        result = asyncio.run(gs.cross_validate(b"x", "POTHOLE", ["POTHOLE"], None))
        assert result.used is False and result.fallback_reason == "GEMINI_API_KEY not configured"


# ---------------------------------------------------------------- GAP-034

class TestEventLoopNotBlocked:
    def test_cpu_bound_preprocessing_runs_off_the_event_loop(self, monkeypatch):
        def slow_preprocess(image_bytes):
            time.sleep(0.6)  # stands in for denoising a large photo
            raise preprocessing.UnprocessableImageError("stop after preprocessing")

        monkeypatch.setattr(preprocessing, "preprocess_image", slow_preprocess)

        async def scenario():
            started = time.monotonic()
            ticks = []

            async def heartbeat():
                while time.monotonic() - started < 0.5:
                    ticks.append(time.monotonic() - started)
                    await asyncio.sleep(0.05)

            classify_task = asyncio.create_task(pipeline.classify(b"img", None, None, None))
            await heartbeat()
            with pytest.raises(preprocessing.UnprocessableImageError):
                await classify_task
            return ticks

        ticks = asyncio.run(scenario())
        # With a blocking call the heartbeat could not tick until 0.6 s had passed.
        assert len(ticks) >= 5 and ticks[1] < 0.3


class TestGeminiHealth:
    def test_health_reports_gemini_configuration_without_calling_it(self):
        body = client.get("/health").json()
        assert body["gemini"]["sdk"] == "google-genai"
        assert body["gemini"]["model"] == get_settings().gemini_model_name
        assert isinstance(body["gemini"]["configured"], bool)
        assert "model_available" in body  # existing contract unchanged

    def test_last_call_reflects_a_real_fallback(self, monkeypatch):
        s = get_settings()
        monkeypatch.setattr(s, "gemini_api_key", "test-key-not-real")

        async def boom(*_a):
            raise RuntimeError("network down")

        monkeypatch.setattr(gs, "_call_gemini", boom)
        asyncio.run(gs.cross_validate(b"x", "POTHOLE", ["POTHOLE"], None))
        last = client.get("/health").json()["gemini"]["last_call"]
        assert last["used"] is False and last["fallback_reason"] == "api_error: RuntimeError"


class TestBackendWireContract:
    """The JSON shapes the Spring backend now sends (SNAKE_CASE ObjectMapper, nulls included)."""

    def test_classify_with_backend_threshold_fields_including_nulls(self):
        body = {
            "image_base64": _b64(_checkerboard(640, 480)),
            "description": None,
            "prior_model_version": None,
            "complaint_id": 42,
            "confidence_threshold": None,
            "category_confidence_thresholds": {"POTHOLE": 75.0, "GARBAGE_OVERFLOW": 80.0},
        }
        r = client.post("/api/v1/ai/classify", json=body, headers=AUTH)
        assert r.status_code == 200, r.text
        payload = r.json()
        assert payload["confidence_threshold_applied"] is not None
        assert "yolo_category" in payload["raw_model_output"]

    def test_classify_with_the_pre_fix_backend_body_still_works(self):
        body = {"image_base64": _b64(_checkerboard(640, 480)), "description": None,
                "prior_model_version": None, "complaint_id": 7}
        assert client.post("/api/v1/ai/classify", json=body, headers=AUTH).status_code == 200

    def test_duplicate_check_with_backend_auto_merge_threshold(self):
        img = _b64(_checkerboard(640, 480))
        body = {
            "complaint_id": 1, "image_base64": img, "latitude": 12.9716, "longitude": 77.5946,
            "candidates": [{"complaint_id": 2, "reference_number": "JN-2", "image_base64": img,
                            "latitude": 12.97162, "longitude": 77.59461, "created_at": "2026-09-25T10:00:00"}],
            "auto_merge_threshold": 70.0,
        }
        r = client.post("/api/v1/ai/duplicate-check", json=body, headers=AUTH)
        assert r.status_code == 200, r.text
        assert r.json()["match_tier"] == "AUTO_MERGE" and r.json()["threshold_used"] == 70.0
