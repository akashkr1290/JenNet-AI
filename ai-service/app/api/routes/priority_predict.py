"""POST /api/v1/ai/priority-predict (SRS 20.3 Table 24, 15.8 Priority Prediction Module)."""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.config import get_settings
from app.core.logging_config import get_audit_logger
from app.schemas.common import ErrorResponse
from app.schemas.priority_predict import PriorityPredictRequest, PriorityPredictResponse
from app.services.priority_service import score_complaint

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])

# SRS 19.6 predictions.model_version - deterministic rules table, not a
# trained model (see priority_service.py module docstring), same honest-
# version-string convention as duplicate_check.py's "dHash-perceptual-v1".
_MODEL_VERSION = "rules-severity-v1"


@router.post(
    "/priority-predict",
    response_model=PriorityPredictResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"}},
    summary="Assign a severity level and priority score to a complaint (SRS 15.8)",
)
async def priority_predict(request: PriorityPredictRequest) -> PriorityPredictResponse:
    settings = get_settings()

    safety_hazard_categories = {
        c.strip().upper()
        for c in settings.priority_safety_hazard_categories.split(",")
        if c.strip()
    }

    result = score_complaint(
        category=request.category.value,
        corroboration_count=request.corroboration_count,
        location_flags=request.location_flags.model_dump(),
        safety_hazard_categories=safety_hazard_categories,
        corroboration_severity_threshold=settings.priority_corroboration_severity_threshold,
    )

    response = PriorityPredictResponse(
        severity=result["severity"],
        priority_score=result["priority_score"],
        safety_hazard_override_applied=result["safety_hazard_override_applied"],
        corroboration_bump_applied=result["corroboration_bump_applied"],
        base_severity=result["base_severity"],
        model_version=_MODEL_VERSION,
        raw_output=result,
    )

    get_audit_logger().info(
        "ai_priority_predict complaint_id=%s category=%s corroboration_count=%s "
        "severity=%s priority_score=%.2f safety_hazard_override=%s corroboration_bump=%s model_version=%s",
        request.complaint_id,
        request.category.value,
        request.corroboration_count,
        response.severity,
        response.priority_score,
        response.safety_hazard_override_applied,
        response.corroboration_bump_applied,
        response.model_version,
    )
    return response
