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

from app.services.yolo_service import get_yolo_service

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    yolo_service = get_yolo_service()
    return {
        "status": "ok",
        "model_available": yolo_service.is_available(),
        "model_unavailable_reason": yolo_service.unavailable_reason(),
    }
