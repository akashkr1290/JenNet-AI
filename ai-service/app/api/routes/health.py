"""
GET /health - liveness/readiness probe.

ADDITION beyond the literal SRS 20.3 contract: not an SRS-named endpoint,
but standard practice for any deployable service (Phase 18 Docker/Phase 21
CI will want something to probe) and harmless to add now. Unauthenticated
by design (infra health checks shouldn't need the internal API key) and
reports model availability so a real deployment can distinguish "service
up, no model loaded" from "service up, fully operational" at a glance.
"""
from __future__ import annotations

from fastapi import APIRouter

from app.config import get_settings
from app.services import gemini_service
from app.services.yolo_service import get_yolo_service

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    yolo_service = get_yolo_service()
    return {
        "status": "ok",
        "model_available": yolo_service.is_available(),
        "model_unavailable_reason": yolo_service.unavailable_reason(),
        # Audit GAP-007: without Gemini every classification is capped below the
        # auto-approve threshold, so its state must be visible. "configured" is
        # only "a key is set"; last_call is the outcome of the latest REAL call
        # (null until one happens) - no probe request is sent from here.
        "gemini": {
            "configured": bool(get_settings().gemini_api_key),
            "model": get_settings().gemini_model_name,
            "sdk": "google-genai",
            "last_call": gemini_service.last_call_status(),
        },
    }
