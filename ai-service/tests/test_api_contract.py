"""
Phase 20: API-level contract tests for every ai-service route, using
FastAPI's TestClient (starlette TestClient, httpx-backed - no real network
socket is opened).

Covers what the existing Phase 7-10 unit tests deliberately did not:
- the internal-API-key auth dependency (SRS 20.3) on every /api/v1/ai/*
  route - present and enforced, wrong key rejected, missing key rejected
- the SRS 20.6 error envelope shape ({error_code, message, details}) is
  actually what callers receive, not just what exceptions.py claims to
  produce
- /health is unauthenticated and reports model_available: false honestly
  (no trained weights ship with this repo - see yolo_service.py)
- request-validation (422) shape for malformed bodies on each endpoint
- the classify/duplicate-check/priority-predict/budget-predict happy paths
  actually round-trip through FastAPI's request/response validation with a
  real (in-repo) settings object and real service-layer code - not just
  the pure functions those unit tests exercise in isolation

Actually executed this phase (see PROJECT_PROGRESS.md's Phase 20 TESTS
section for the environment-capability note re: pip/pytest now being
reachable). Requires: fastapi, httpx, pytest (all in requirements.txt).
"""
from __future__ import annotations

import base64

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app

client = TestClient(app)

VALID_KEY = get_settings().ai_service_api_key  # default "change-me-in-every-real-environment"
AUTH_HEADER = {"X-Internal-Api-Key": VALID_KEY}


def _real_png_base64(width: int, height: int, value: int = 128) -> str:
    """A genuinely OpenCV-decodable solid-color PNG, base64-encoded. Built
    with cv2 itself (not hand-crafted bytes) so it always round-trips
    correctly regardless of PNG-encoder internals."""
    import cv2

    image = np.full((height, width, 3), value, dtype=np.uint8)
    ok, buf = cv2.imencode(".png", image)
    assert ok
    return base64.b64encode(buf.tobytes()).decode()


def _tiny_valid_png_base64() -> str:
    """A real, decodable 50x50 PNG - passes decode but is TOO_SMALL for the
    quality gate (below the 320x320 minimum). Good for exercising the
    422 UNPROCESSABLE_IMAGE path at the HTTP layer without needing to
    clear the full quality gate (the pure-function preprocessing tests
    already cover that math in isolation)."""
    return _real_png_base64(50, 50)


class TestHealthEndpoint:
    def test_health_is_unauthenticated(self):
        resp = client.get("/health")
        assert resp.status_code == 200

    def test_health_reports_model_availability_honestly(self):
        # Gap-backlog Patch 1 (Sep 2026 audit): models/yolov11-civic-v1.0.pt
        # now genuinely ships with this repo and settings.yolo_model_path
        # correctly points at it (a prior filename mismatch used to defeat
        # this silently - see decisions-and-principles.md) - so the
        # honesty guarantee this test protects now cuts the other way:
        # model_available must be True with real weights present, not
        # fabricated False.
        resp = client.get("/health")
        body = resp.json()
        assert body["status"] == "ok"
        assert body["model_available"] is True
        assert body["model_unavailable_reason"] is None


class TestInternalApiKeyAuth:
    """SRS 20.3: every /api/v1/ai/* route requires X-Internal-Api-Key."""

    @pytest.mark.parametrize(
        "method,path,body",
        [
            ("post", "/api/v1/ai/classify", {"image_base64": "x"}),
            ("post", "/api/v1/ai/duplicate-check", {"complaint_id": 1}),
            ("post", "/api/v1/ai/priority-predict", {"category": "POTHOLE"}),
            ("post", "/api/v1/ai/budget-predict", {"category": "POTHOLE", "severity": "MEDIUM"}),
        ],
    )
    def test_missing_api_key_is_rejected(self, method, path, body):
        resp = client.post(path, json=body)
        assert resp.status_code == 401
        envelope = resp.json()
        assert envelope["error_code"] == "UNAUTHORIZED"
        assert "message" in envelope

    @pytest.mark.parametrize(
        "path,body",
        [
            ("/api/v1/ai/classify", {"image_base64": "x"}),
            ("/api/v1/ai/duplicate-check", {"complaint_id": 1}),
            ("/api/v1/ai/priority-predict", {"category": "POTHOLE"}),
            ("/api/v1/ai/budget-predict", {"category": "POTHOLE", "severity": "MEDIUM"}),
        ],
    )
    def test_wrong_api_key_is_rejected(self, path, body):
        resp = client.post(path, json=body, headers={"X-Internal-Api-Key": "totally-wrong-key"})
        assert resp.status_code == 401

    def test_correct_api_key_is_accepted_for_priority_predict(self):
        # priority-predict has no external I/O (no image, no HTTP fetch) so
        # it's the cleanest happy-path proof that auth + routing + the real
        # service layer all wire together correctly end-to-end.
        resp = client.post(
            "/api/v1/ai/priority-predict",
            json={"category": "POTHOLE", "corroboration_count": 1},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 200


class TestErrorEnvelopeShape:
    """SRS 20.6: {error_code, message, details} on every error response."""

    def test_validation_error_on_missing_required_field(self):
        # budget-predict requires `category` and `severity`; omit both.
        resp = client.post("/api/v1/ai/budget-predict", json={}, headers=AUTH_HEADER)
        assert resp.status_code == 422

    def test_unprocessable_image_envelope_shape(self):
        resp = client.post(
            "/api/v1/ai/classify",
            json={"image_base64": _tiny_valid_png_base64()},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 422
        envelope = resp.json()
        assert set(envelope.keys()) == {"error_code", "message", "details"}
        assert envelope["error_code"] == "UNPROCESSABLE_IMAGE"
        assert envelope["details"]["quality_flag"] == "TOO_SMALL"

    def test_classify_rejects_both_image_sources_provided(self):
        resp = client.post(
            "/api/v1/ai/classify",
            json={"image_url": "https://example.com/a.jpg", "image_base64": "abc"},
            headers=AUTH_HEADER,
        )
        # Pydantic model_validator raises ValueError -> FastAPI turns this
        # into its own native 422, not our custom envelope (unlike the
        # UnprocessableImageError case above, which happens after
        # validation succeeds, inside the route body).
        assert resp.status_code == 422

    def test_classify_rejects_neither_image_source_provided(self):
        resp = client.post(
            "/api/v1/ai/classify",
            json={"description": "no image at all"},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 422


class TestPriorityPredictHappyPath:
    def test_open_manhole_forces_critical_via_http(self):
        resp = client.post(
            "/api/v1/ai/priority-predict",
            json={"category": "OPEN_MANHOLE", "corroboration_count": 1},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["severity"] == "CRITICAL"
        assert body["safety_hazard_override_applied"] is True
        assert body["model_version"]


class TestBudgetPredictHappyPath:
    def test_pothole_medium_matches_service_layer_exactly(self):
        resp = client.post(
            "/api/v1/ai/budget-predict",
            json={"category": "POTHOLE", "severity": "MEDIUM"},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 200
        body = resp.json()
        # Cross-check against test_budget_service.py's known-good numbers
        # for the exact same inputs - proves the HTTP layer doesn't
        # transform the service layer's output.
        assert body["estimated_cost_min"] == 2000.0
        assert body["estimated_cost_max"] == 15000.0
        assert body["guardrail_applied"] is False


class TestDuplicateCheckHappyPath:
    def test_no_candidates_is_not_an_error(self):
        # duplicate-check's OWN image (the new submission's) still must be
        # a valid, decodable image even with zero candidates to compare
        # against - only compute_perceptual_hash is called on it, which
        # has no minimum-size quality gate the way /classify's
        # preprocessing pipeline does, so a small solid-color PNG is fine.
        resp = client.post(
            "/api/v1/ai/duplicate-check",
            json={
                "complaint_id": 1,
                "image_base64": _real_png_base64(64, 64),
                "candidates": [],
            },
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["is_duplicate"] is False
        assert body["similarity_score"] == 0
        assert body["requires_manual_review"] is False


class TestBinaryBodyRegression:
    """Final recheck (Sep 2026): a live uvicorn run returned HTTP 500 when a
    non-UTF-8 binary body was posted to /classify (multipart instead of the
    documented JSON contract). It must be a clean 422 in the SRS 20.6
    envelope, and must not echo the raw bytes back."""

    def test_multipart_binary_body_is_422_not_500(self):
        jpeg_like = b"\xff\xd8\xff\xe0" + bytes(range(256)) * 4
        resp = client.post(
            "/api/v1/ai/classify",
            files={"image": ("photo.jpg", jpeg_like, "image/jpeg")},
            headers=AUTH_HEADER,
        )
        assert resp.status_code == 422
        body = resp.json()
        assert set(body.keys()) == {"error_code", "message", "details"}
        assert body["error_code"] == "VALIDATION_ERROR"
        assert "\\xff" not in resp.text and "input" not in resp.text

    def test_malformed_json_field_types_still_422_envelope(self):
        resp = client.post("/api/v1/ai/budget-predict", json={"category": 5}, headers=AUTH_HEADER)
        assert resp.status_code == 422
        assert resp.json()["error_code"] == "VALIDATION_ERROR"
