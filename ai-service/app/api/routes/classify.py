"""POST /api/v1/ai/classify (SRS 20.3)."""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.core.image_fetch import fetch_image_bytes
from app.schemas.classify import ClassifyRequest, ClassifyResponse
from app.schemas.common import ErrorResponse
from app.services.pipeline import classify as run_pipeline

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])


@router.post(
    "/classify",
    response_model=ClassifyResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"},
        422: {"model": ErrorResponse, "description": "Unprocessable image"},
        504: {"model": ErrorResponse, "description": "Model timeout"},
    },
    summary="Classify a complaint image (SRS 15.4 AI Analysis Module)",
)
async def classify_image(request: ClassifyRequest) -> ClassifyResponse:
    image_bytes = await fetch_image_bytes(request.image_url, request.image_base64)
    return await run_pipeline(
        image_bytes=image_bytes,
        citizen_description=request.description,
        prior_model_version=request.prior_model_version,
        complaint_id=request.complaint_id,
    )
