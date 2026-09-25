"""POST /api/v1/ai/quality - intake image quality check (audit GAP-032).

SRS 21.3: an unusable photo is "rejected at intake with a citizen-facing prompt
to retake the photo, before any model inference". The backend calls this
BEFORE it stores a complaint; no model, OCR or Gemini work is done here.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, model_validator

from app.api.deps import require_internal_api_key
from app.config import get_settings
from app.core.image_fetch import fetch_image_bytes
from app.schemas.classify import ImageQualityFlag
from app.schemas.common import ErrorResponse
from app.services.preprocessing import assess_image_quality

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])

_RETAKE_MESSAGES = {
    ImageQualityFlag.BLURRY: "The photo is too blurry. Please hold the camera steady and retake it.",
    ImageQualityFlag.TOO_SMALL: "The photo resolution is too low (minimum 480p). Please retake it with the camera.",
}


class QualityRequest(BaseModel):
    image_url: str | None = Field(default=None)
    image_base64: str | None = Field(default=None)

    @model_validator(mode="after")
    def _exactly_one_image_source(self) -> "QualityRequest":
        if bool(self.image_url) == bool(self.image_base64):
            raise ValueError("Provide exactly one of image_url or image_base64.")
        return self


class QualityResponse(BaseModel):
    acceptable: bool
    quality_flag: ImageQualityFlag
    width: int
    height: int
    min_width_px: int
    min_height_px: int
    blur_variance: float
    message: str | None = Field(default=None, description="Citizen-facing retake prompt when not acceptable.")


@router.post(
    "/quality",
    response_model=QualityResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"},
        422: {"model": ErrorResponse, "description": "Bytes are not a decodable image"},
    },
    summary="Check photo quality before a complaint is created (SRS 21.3 intake gate)",
)
async def check_quality(request: QualityRequest) -> QualityResponse:
    settings = get_settings()
    image_bytes = await fetch_image_bytes(request.image_url, request.image_base64)
    report = await asyncio.to_thread(assess_image_quality, image_bytes)
    return QualityResponse(
        acceptable=report.acceptable,
        quality_flag=report.quality_flag,
        width=report.width,
        height=report.height,
        min_width_px=settings.min_image_width_px,
        min_height_px=settings.min_image_height_px,
        blur_variance=round(report.blur_variance, 2),
        message=None if report.acceptable else _RETAKE_MESSAGES.get(report.quality_flag),
    )
