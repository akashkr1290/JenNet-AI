"""POST /api/v1/ai/budget-predict (SRS 20.3 Table 24, 15.9 Budget Prediction Module)."""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.config import get_settings
from app.core.logging_config import get_audit_logger
from app.schemas.budget_predict import BudgetPredictRequest, BudgetPredictResponse
from app.schemas.common import ErrorResponse
from app.services.budget_service import estimate_budget

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])

# Same honest-version-string convention as priority_predict.py/duplicate_check.py.
_MODEL_VERSION = "rules-budget-v1"


@router.post(
    "/budget-predict",
    response_model=BudgetPredictResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"}},
    summary="Estimate repair cost range and resolution time for a complaint (SRS 15.9)",
)
async def budget_predict(request: BudgetPredictRequest) -> BudgetPredictResponse:
    settings = get_settings()

    result = estimate_budget(
        category=request.category.value,
        severity=request.severity.upper(),
        guardrail_min=settings.budget_guardrail_min_inr,
        guardrail_max=settings.budget_guardrail_max_inr,
    )

    response = BudgetPredictResponse(
        estimated_cost_min=result["estimated_cost_min"],
        estimated_cost_max=result["estimated_cost_max"],
        estimated_resolution_days=result["estimated_resolution_days"],
        confidence=result["confidence"],
        model_version=_MODEL_VERSION,
        guardrail_applied=result["guardrail_applied"],
        raw_output=result,
    )

    get_audit_logger().info(
        "ai_budget_predict complaint_id=%s category=%s severity=%s ward_id=%s "
        "estimated_cost_min=%.2f estimated_cost_max=%.2f estimated_resolution_days=%s "
        "confidence=%s guardrail_applied=%s model_version=%s",
        request.complaint_id,
        request.category.value,
        request.severity,
        request.ward_id,
        response.estimated_cost_min,
        response.estimated_cost_max,
        response.estimated_resolution_days,
        response.confidence,
        response.guardrail_applied,
        response.model_version,
    )
    return response
