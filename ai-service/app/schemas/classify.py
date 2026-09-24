"""
Request/response schemas for POST /api/v1/ai/classify (SRS 20.3, 15.4, 21.1-21.4).

`IssueCategory` intentionally mirrors
backend `ComplaintCategory` (PROJECT_INTEGRATION.md Section 3) value-for-value
so Phase 8 can deserialize this response directly onto that enum with no
translation table.
"""
from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field, model_validator


class IssueCategory(str, Enum):
    POTHOLE = "POTHOLE"
    GARBAGE_OVERFLOW = "GARBAGE_OVERFLOW"
    WATER_LEAKAGE = "WATER_LEAKAGE"
    BROKEN_STREET_LIGHT = "BROKEN_STREET_LIGHT"
    OPEN_MANHOLE = "OPEN_MANHOLE"
    ILLEGAL_CONSTRUCTION = "ILLEGAL_CONSTRUCTION"
    GENERAL = "GENERAL"


class ImageQualityFlag(str, Enum):
    """SRS 21.3 OpenCV output values."""

    ACCEPTABLE = "ACCEPTABLE"
    BLURRY = "BLURRY"
    TOO_DARK = "TOO_DARK"
    TOO_SMALL = "TOO_SMALL"


class AiStatus(str, Enum):
    """Gap-backlog Patch 30 (Sep 2026 audit): a human-readable status
    string for the citizen/officer-facing explainability UI (Patch 43's
    "AI Status: Automatically Classified" example), derived from the
    same requires_manual_review/model_available facts the API already
    computes - not a new decision, just a readable label for an existing
    one.
    """

    AUTO_CLASSIFIED = "AUTO_CLASSIFIED"
    MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"


class OcrStatus(str, Enum):
    """Gap-backlog Patch 3 (Sep 2026 audit): the 3-way distinction SRS
    21.4's own external contract collapses (see ocr_service.py's module
    docstring for why that collapse was originally correct for
    ocr_text's own value) but which operators/callers now get as an
    explicit field instead of only via log lines.
    """

    SUCCESS = "SUCCESS"
    NO_TEXT = "NO_TEXT"
    UNAVAILABLE = "UNAVAILABLE"


class ClassifyRequest(BaseModel):
    # SRS 20.3 literal contract field. Exactly one of image_url/image_base64
    # is required - see app/core/image_fetch.py for why image_base64 exists.
    image_url: str | None = Field(
        default=None, description="Fetchable reference to the stored complaint image."
    )
    image_base64: str | None = Field(
        default=None,
        description=(
            "Phase-7-only alternate input, base64-encoded image bytes. "
            "Not part of the SRS 20.3 literal contract - see "
            "app/core/image_fetch.py docstring."
        ),
    )
    description: str | None = Field(
        default=None,
        max_length=500,
        description="Optional citizen-provided text description (SRS 15.4 Inputs).",
    )
    prior_model_version: str | None = Field(
        default=None,
        description=(
            "Optional prior model version metadata (SRS 15.4 Inputs) - e.g. "
            "the model_version of an earlier prediction attempt for this "
            "complaint, if this is a retry."
        ),
    )
    complaint_id: int | None = Field(
        default=None,
        description=(
            "ADDITION beyond the literal SRS 20.3 contract: correlates this "
            "call with a complaint for the audit log (see "
            "core/logging_config.py). Optional so the endpoint remains "
            "independently callable/testable ahead of Phase 8's real "
            "backend<->ai-service wiring."
        ),
    )

    @model_validator(mode="after")
    def _exactly_one_image_source(self) -> "ClassifyRequest":
        # Mirrors the check in image_fetch.fetch_image_bytes; enforced here
        # too so FastAPI/OpenAPI surfaces a 422 with a clear message before
        # the pipeline is ever invoked.
        if bool(self.image_url) == bool(self.image_base64):
            raise ValueError("Provide exactly one of image_url or image_base64.")
        return self


class ClassifyResponse(BaseModel):
    # --- SRS 15.4 Outputs / 20.3 literal response fields ---
    category: IssueCategory
    confidence: float = Field(..., ge=0, le=100, description="Overall confidence score, 0-100.")
    gemini_description: str | None = Field(
        default=None, description="Gemini-generated human-readable description, if available."
    )
    ocr_text: str | None = Field(default=None, description="Extracted embedded text, if any.")
    ocr_status: OcrStatus = Field(
        default=OcrStatus.NO_TEXT,
        description=(
            "Gap-backlog Patch 3 (Sep 2026 audit): SUCCESS (text found), "
            "NO_TEXT (engine ran, found nothing), or UNAVAILABLE (engine/"
            "binary not usable) - distinguishes the two cases SRS 21.4's "
            "ocr_text=null contract alone can't."
        ),
    )
    preprocessed_image_reference: str | None = Field(
        default=None,
        description=(
            "Reference to the pre-processed image. Always null in Phase 7: "
            "this service has no persistent storage integration yet (that's "
            "a Phase 8 concern) - see KNOWN LIMITATIONS in PROJECT_PROGRESS.md."
        ),
    )

    # --- Additions beyond the literal SRS output list, needed for Phase 8
    # to actually implement the routing business rule in SRS 15.4
    # ("classification confidence below 85% ... routes to Verification
    # Team; ... at or above threshold proceeds automatically"). Recorded in
    # PROJECT_INTEGRATION.md Section 6.
    requires_manual_review: bool = Field(
        ...,
        description=(
            "True if confidence is below auto_approve_confidence_threshold, "
            "or no model is available/loaded, or detection failed outright - "
            "the caller should route to the Verification Team queue rather "
            "than auto-approving."
        ),
    )
    routing_reason: str = Field(
        ..., description="Machine-readable reason code explaining requires_manual_review."
    )
    model_version: str = Field(..., description="SRS 19.6 predictions.model_version value.")
    model_available: bool = Field(
        ..., description="False when no trained YOLOv11 weights are loaded (see models/README.md)."
    )
    image_quality_flag: ImageQualityFlag
    gemini_used: bool = Field(..., description="Whether Gemini was actually called (vs. skipped/fallback).")
    top_candidates: list[dict[str, Any]] = Field(
        default_factory=list,
        description=(
            "Top-3 candidate classes with per-class scores (SRS 21.1 "
            "fallback logic: surfaced for manual selection when nothing "
            "clears the minimum detection threshold)."
        ),
    )
    raw_model_output: dict[str, Any] = Field(
        ...,
        description=(
            "Full pipeline output for audit/retraining - maps directly onto "
            "predictions.raw_model_output (SRS 19.6 / V8 migration, JSON column)."
        ),
    )

    # --- Gap-backlog Patches 24/30 (Sep 2026 audit) ---
    ai_status: AiStatus = Field(
        ...,
        description=(
            "Human-readable classification status for citizen/officer-facing "
            "UI (Gap-backlog Patch 30/43's 'AI Status: Automatically "
            "Classified' example) - derived from requires_manual_review/"
            "model_available, not a new decision."
        ),
    )
    timing_ms: dict[str, float] = Field(
        default_factory=dict,
        description=(
            "Gap-backlog Patch 24/32 (Sep 2026 audit): per-stage wall-clock "
            "breakdown (preprocessing, yolo_inference, gemini, ocr, total) "
            "in milliseconds - a single-request anecdotal timing, not a "
            "load-tested P50/P95/P99 distribution (see Gap-backlog Patch 54)."
        ),
    )
