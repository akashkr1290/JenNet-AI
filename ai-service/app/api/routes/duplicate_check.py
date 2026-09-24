"""POST /api/v1/ai/duplicate-check (SRS 20.3, 15.6 Duplicate Detection Module)."""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.config import get_settings
from app.core.image_fetch import fetch_image_bytes
from app.core.logging_config import get_audit_logger
from app.schemas.common import ErrorResponse
from app.schemas.duplicate_check import DuplicateCheckRequest, DuplicateCheckResponse
from app.services.duplicate_service import build_response, compute_perceptual_hash, score_candidate

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])

# SRS 19.6 predictions.model_version - this capability has no trained-model
# version to report (it's deterministic dHash comparison, not a learned
# model), so a stable, self-describing version string is used instead -
# same purpose as yolo_service's model_version field, honestly labelled.
_MODEL_VERSION = "dHash-perceptual-v1"


@router.post(
    "/duplicate-check",
    response_model=DuplicateCheckResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"},
        422: {"model": ErrorResponse, "description": "Unprocessable image (the new submission's own photo)"},
    },
    summary="Check a new complaint's image+GPS against existing open complaints (SRS 15.6)",
)
async def duplicate_check(request: DuplicateCheckRequest) -> DuplicateCheckResponse:
    settings = get_settings()

    # The new submission's own image MUST decode - this mirrors /classify's
    # 422 UnprocessableImageError behavior (an undecodable image is a real
    # client error, not something to silently skip). fetch_image_bytes
    # already raises ImageFetchError/InvalidRequestError (-> 422/400) for a
    # bad image_url/image_base64; a bytes-decoded-but-not-a-real-image case
    # is handled by compute_perceptual_hash raising ValueError below.
    new_image_bytes = await fetch_image_bytes(request.image_url, request.image_base64)
    try:
        new_hash = compute_perceptual_hash(new_image_bytes)
    except ValueError as exc:
        from app.core.exceptions import UnprocessableImageError
        raise UnprocessableImageError(str(exc)) from exc

    new_now = datetime.now(timezone.utc)
    matches = []
    skipped: list[dict] = []

    for candidate in request.candidates:
        # Per-candidate resilience: one bad candidate image (e.g. a photo
        # that went missing on the backend's storage, or a corrupt upload
        # from a much earlier phase) must not fail the whole duplicate
        # check for the new, otherwise-healthy submission - it's simply
        # excluded from scoring and recorded in raw_output for audit,
        # exactly the same "never let an AI-module hiccup block a citizen
        # workflow" principle Phase 8's AiClassificationService established
        # for the classify endpoint's own failure modes.
        try:
            candidate_bytes = await fetch_image_bytes(candidate.image_url, candidate.image_base64)
            candidate_hash = compute_perceptual_hash(candidate_bytes)
        except Exception as exc:  # noqa: BLE001 - deliberately broad, see comment above
            skipped.append({"complaint_id": candidate.complaint_id, "reason": str(exc)})
            continue

        matches.append(
            score_candidate(
                new_hash=new_hash,
                new_lat=request.latitude,
                new_lon=request.longitude,
                new_now=new_now,
                candidate=candidate,
                candidate_hash=candidate_hash,
                settings=settings,
            )
        )

    response = build_response(
        new_lat=request.latitude,
        new_lon=request.longitude,
        matches=matches,
        model_version=_MODEL_VERSION,
    )
    if skipped:
        response.raw_output["skipped_candidates"] = skipped

    _log_audit_record(request.complaint_id, response, len(request.candidates), len(skipped))
    return response


def _log_audit_record(complaint_id: int, response: DuplicateCheckResponse, candidate_count: int, skipped_count: int) -> None:
    # SRS 15.6 mirrors 15.4's "every AI decision is logged" business rule.
    get_audit_logger().info(
        "ai_duplicate_check complaint_id=%s is_duplicate=%s parent_complaint_id=%s "
        "similarity_score=%.2f match_tier=%s requires_manual_review=%s "
        "candidate_count=%s skipped_count=%s model_version=%s",
        complaint_id,
        response.is_duplicate,
        response.parent_complaint_id,
        response.similarity_score,
        response.match_tier.value,
        response.requires_manual_review,
        candidate_count,
        skipped_count,
        response.model_version,
    )
