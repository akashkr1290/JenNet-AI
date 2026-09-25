"""
Orchestrates the full classification pipeline (SRS 15.4):

    OpenCV pre-processing -> YOLOv11 classification -> Gemini cross-
    validation -> OCR extraction -> confidence scoring -> routing decision

This is the one place SRS 15.4's Business Rules are actually enforced:

    "classification confidence below 85% (configurable) routes to
    Verification Team; classification confidence at or above threshold
    proceeds automatically; every AI decision is logged with model
    version, confidence score, and raw output for auditability and future
    model retraining."
"""
from __future__ import annotations

import asyncio
import threading
import time
from typing import Any

from app.config import active_model, get_settings
from app.core.logging_config import get_audit_logger
from app.schemas.classify import AiStatus, ClassifyResponse, ImageQualityFlag, IssueCategory
from app.services import gemini_service, ocr_service, preprocessing
from app.services.model_monitor import get_model_monitor
from app.services.yolo_service import MODEL_INPUT_SIZE, DetectionResult, get_yolo_service

# Gemini agreement/disagreement adjustments (SRS 21.2 Confidence Score:
# "Gemini's agreement/disagreement... adjusts the overall confidence score
# upward (on agreement) or downward (on disagreement)"). The SRS names the
# direction but not a magnitude; these are documented, configurable-in-code
# placeholders pending real model/Gemini data to tune against - see
# PROJECT_INTEGRATION.md Section 6.
_GEMINI_AGREEMENT_BOOST = 8.0
_GEMINI_DISAGREEMENT_PENALTY = 25.0

# Audit GAP-034: inference now runs in worker threads; an Ultralytics predictor
# is not safe to call from several threads at once, so calls are serialised.
# Pre-processing, OCR and Gemini still run concurrently, and the event loop
# (including /health) stays responsive.
_YOLO_LOCK = threading.Lock()


def _classify_serialised(yolo_service, image):
    with _YOLO_LOCK:
        return yolo_service.classify(image)


async def classify(
    image_bytes: bytes,
    citizen_description: str | None,
    prior_model_version: str | None,
    complaint_id: int | None,
    confidence_threshold: float | None = None,
    category_confidence_thresholds: dict[str, float] | None = None,
) -> ClassifyResponse:
    settings = get_settings()
    started_at = time.monotonic()
    # Gap-backlog Patch 24/32 (Sep 2026 audit): per-stage timing - real
    # wall-clock measurements taken around each stage below, not
    # estimated.
    timing_ms: dict[str, float] = {}

    # 1. Pre-processing / quality gate (SRS 21.3). Raises UnprocessableImageError
    #    (-> 422) before any model inference if the image is rejected at intake.
    _stage_started = time.monotonic()
    # Audit GAP-034: CPU-bound OpenCV/YOLO/OCR work runs in a worker thread so
    # this async handler never blocks the event loop (other requests, /health).
    preprocessed = await asyncio.to_thread(preprocessing.preprocess_image, image_bytes)
    timing_ms["preprocessing"] = round((time.monotonic() - _stage_started) * 1000, 1)

    # 2. YOLOv11 classification (SRS 21.1).
    _stage_started = time.monotonic()
    yolo_service = get_yolo_service()
    model_available = yolo_service.is_available()
    detections: list[DetectionResult] = (
        await asyncio.to_thread(_classify_serialised, yolo_service, preprocessed.normalized_image)
        if model_available else []
    )
    timing_ms["yolo_inference"] = round((time.monotonic() - _stage_started) * 1000, 1)
    top_detection = detections[0] if detections else None
    top_candidates = [d.to_dict() for d in detections[:3]]
    candidate_class_names = [d.class_name for d in detections[:3]]

    # 3. Gemini cross-validation (SRS 21.2) - only meaningful with a top candidate.
    _stage_started = time.monotonic()
    gemini_result = await gemini_service.cross_validate(
        image_bytes=image_bytes,
        top_candidate_class=top_detection.class_name if top_detection else None,
        candidate_classes=candidate_class_names,
        citizen_description=citizen_description,
    )
    timing_ms["gemini"] = round((time.monotonic() - _stage_started) * 1000, 1)

    # 4. OCR extraction (SRS 21.4) - independent of classification outcome.
    _stage_started = time.monotonic()
    ocr_text, ocr_status = await asyncio.to_thread(ocr_service.extract_text, preprocessed.normalized_image)
    timing_ms["ocr"] = round((time.monotonic() - _stage_started) * 1000, 1)

    # 5. Confidence scoring + routing decision (SRS 15.4, 21.1, 21.2).
    yolo_category = _map_category(top_detection.class_name if top_detection else None)
    # Audit GAP-011: the threshold in force for the category being approved.
    threshold = resolve_confidence_threshold(
        yolo_category.value, confidence_threshold, category_confidence_thresholds, settings
    )
    confidence, routing_reason, requires_manual_review = _score_and_route(
        model_available=model_available,
        top_detection=top_detection,
        gemini_result=gemini_result,
        settings=settings,
        threshold=threshold,
    )

    # Audit GAP-053: use Gemini's revised category / the OCR hint - never to
    # auto-approve, only so the Verification Team starts from the best guess.
    ocr_hint = _optional_category(ocr_service.ocr_category_hint(ocr_text))
    gemini_category = _optional_category(getattr(gemini_result, "suggested_category", None))
    category, routing_reason, requires_manual_review = _apply_category_revisions(
        yolo_category=yolo_category,
        top_detection=top_detection,
        gemini_result=gemini_result,
        gemini_category=gemini_category,
        ocr_hint=ocr_hint,
        routing_reason=routing_reason,
        requires_manual_review=requires_manual_review,
    )
    elapsed_ms = round((time.monotonic() - started_at) * 1000, 1)
    timing_ms["total"] = elapsed_ms

    # Gap-backlog Patch 30 (Sep 2026 audit): human-readable status,
    # derived from the same facts routing_reason/requires_manual_review
    # already carry - not a new decision.
    if not model_available:
        ai_status = AiStatus.MODEL_UNAVAILABLE
    elif requires_manual_review:
        ai_status = AiStatus.MANUAL_REVIEW_REQUIRED
    else:
        ai_status = AiStatus.AUTO_CLASSIFIED

    raw_model_output: dict[str, Any] = {
        # Remaining-gaps item 12: per-stage inference timing inside the payload
        # the backend already persists (predictions.raw_model_output), so
        # latency survives restarts and is reported by the backend's
        # /api/v1/admin/ai-feedback/monitoring summary.
        "timing_ms": dict(timing_ms),
        "yolo": {
            "model_available": model_available,
            "unavailable_reason": yolo_service.unavailable_reason() if not model_available else None,
            "detections": [d.to_dict() for d in detections],
            "min_detection_threshold": settings.min_detection_threshold,
            # Gap-backlog Patch 42: bounding boxes are in this (width, height)
            # space - preprocessing resizes (no letterbox) to it, so dividing by
            # it gives coordinates relative to the original uploaded photo.
            "model_input_size": list(MODEL_INPUT_SIZE),
        },
        "gemini": {
            "used": gemini_result.used,
            "description": gemini_result.description,
            "agrees_with_top_candidate": gemini_result.agrees_with_top_candidate,
            "fallback_reason": gemini_result.fallback_reason,
            "suggested_category": gemini_category.value if gemini_category else None,
        },
        "yolo_category": yolo_category.value,
        "confidence_threshold_applied": threshold,
        "ocr_category_hint": ocr_hint.value if ocr_hint else None,
        "ocr": {"text_found": ocr_text is not None, "status": ocr_status.value},
        "preprocessing": {
            "quality_flag": preprocessed.quality_flag.value,
            "width": preprocessed.width,
            "height": preprocessed.height,
            "blur_variance": round(preprocessed.blur_variance, 2),
        },
        "prior_model_version": prior_model_version,
        "elapsed_ms": elapsed_ms,
    }

    response = ClassifyResponse(
        category=category,
        confidence=confidence,
        gemini_description=gemini_result.description,
        ocr_text=ocr_text,
        ocr_status=ocr_status,
        ai_status=ai_status,
        timing_ms=timing_ms,
        preprocessed_image_reference=None,  # Phase 8 storage-integration concern.
        requires_manual_review=requires_manual_review,
        routing_reason=routing_reason,
        model_version=active_model(settings)[1],  # remaining-gaps item 13
        model_available=model_available,
        image_quality_flag=preprocessed.quality_flag,
        gemini_used=gemini_result.used,
        top_candidates=top_candidates,
        raw_model_output=raw_model_output,
        confidence_threshold_applied=threshold,
        gemini_suggested_category=gemini_category,
        ocr_category_hint=ocr_hint,
    )

    _log_audit_record(complaint_id, response, elapsed_ms)
    # Gap-backlog Patch 36 (Sep 2026 audit): feed the in-process monitor
    # from the exact same response every caller sees - never a separate
    # recomputation that could drift from what was actually returned.
    get_model_monitor().record(
        category=response.category.value,
        confidence=response.confidence,
        requires_manual_review=response.requires_manual_review,
        model_available=response.model_available,
    )
    return response


def resolve_confidence_threshold(
    category: str,
    confidence_threshold: float | None,
    category_confidence_thresholds: dict[str, float] | None,
    settings,
) -> float:
    """Audit GAP-011: routing rule for the category -> platform setting -> env default."""
    if category_confidence_thresholds:
        value = category_confidence_thresholds.get(category)
        if value is not None:
            return float(value)
    if confidence_threshold is not None:
        return float(confidence_threshold)
    return float(settings.auto_approve_confidence_threshold)


def _optional_category(name: str | None) -> IssueCategory | None:
    if not name:
        return None
    try:
        return IssueCategory[name]
    except KeyError:
        return None


def _apply_category_revisions(
    *, yolo_category: IssueCategory, top_detection, gemini_result, gemini_category,
    ocr_hint, routing_reason: str, requires_manual_review: bool,
) -> tuple[IssueCategory, str, bool]:
    """Audit GAP-053 (SRS 21.2 "confirmed or revised classification", 21.4)."""
    if (
        top_detection is not None
        and gemini_result.used
        and gemini_result.agrees_with_top_candidate is False
        and gemini_category is not None
        and gemini_category != yolo_category
    ):
        # Disagreement with a concrete alternative: the revised category is
        # what the Verification Team sees; it is never auto-approved.
        return gemini_category, "GEMINI_REVISED_CATEGORY", True
    if top_detection is None and ocr_hint is not None:
        return ocr_hint, routing_reason, True
    return yolo_category, routing_reason, requires_manual_review


def _score_and_route(
    *, model_available: bool, top_detection: DetectionResult | None, gemini_result, settings,
    threshold: float | None = None,
) -> tuple[float, str, bool]:
    auto_approve_threshold = settings.auto_approve_confidence_threshold if threshold is None else threshold
    if not model_available:
        return 0.0, "MODEL_UNAVAILABLE", True

    if top_detection is None:
        # SRS 21.1 Fallback Logic: no class exceeded the minimum detection
        # threshold -> route directly to Verification Team with the raw
        # image and top-3 candidates (top_candidates will be empty here
        # since nothing cleared the threshold at all).
        return 0.0, "NO_DETECTION_ABOVE_THRESHOLD", True

    confidence = top_detection.confidence

    if gemini_result.used:
        if gemini_result.agrees_with_top_candidate is True:
            confidence = min(100.0, confidence + _GEMINI_AGREEMENT_BOOST)
        elif gemini_result.agrees_with_top_candidate is False:
            confidence = max(0.0, confidence - _GEMINI_DISAGREEMENT_PENALTY)
    else:
        # SRS 15.4 Exceptions / 21.2 Fallback Logic: Gemini unavailable ->
        # confidence capped, forcing manual review if it would otherwise
        # have auto-qualified.
        confidence = min(confidence, settings.gemini_fallback_confidence_cap)

    if confidence < auto_approve_threshold:
        reason = (
            "GEMINI_DISAGREEMENT"
            if gemini_result.used and gemini_result.agrees_with_top_candidate is False
            else "BELOW_AUTO_APPROVE_THRESHOLD"
        )
        return confidence, reason, True

    return confidence, "AUTO_APPROVED", False


def _map_category(yolo_class_name: str | None) -> IssueCategory:
    if not yolo_class_name:
        return IssueCategory.GENERAL
    try:
        return IssueCategory[yolo_class_name.strip().upper()]
    except KeyError:
        return IssueCategory.GENERAL


def _log_audit_record(complaint_id: int | None, response: ClassifyResponse, elapsed_ms: float) -> None:
    # SRS 15.4 Business Rules: "every AI decision is logged with model
    # version, confidence score, and raw output for auditability and
    # future model retraining."
    get_audit_logger().info(
        "ai_classification complaint_id=%s category=%s confidence=%.2f "
        "requires_manual_review=%s routing_reason=%s model_version=%s "
        "model_available=%s elapsed_ms=%s",
        complaint_id,
        response.category.value,
        response.confidence,
        response.requires_manual_review,
        response.routing_reason,
        response.model_version,
        response.model_available,
        elapsed_ms,
    )
