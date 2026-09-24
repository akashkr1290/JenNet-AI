"""GET /api/v1/ai/monitoring/summary (Gap-backlog Patch 36, Sep 2026 audit).

Same auth as /classify (internal API key) - this exposes operational
prediction data, not a public health check, so it follows /classify's
auth pattern rather than /health's unauthenticated one.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.services.model_monitor import get_model_monitor

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])


@router.get(
    "/monitoring/summary",
    dependencies=[Depends(require_internal_api_key)],
    summary="In-process model prediction/confidence monitoring summary (Gap-backlog Patch 36)",
)
async def monitoring_summary() -> dict:
    return get_model_monitor().summary()
