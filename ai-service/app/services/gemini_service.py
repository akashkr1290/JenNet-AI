"""
Gemini API contextual reasoning wrapper (SRS 15.4 Features, 21.2).

Cross-validates the YOLOv11 classification using broader scene context and
generates a human-readable description. Per SRS 15.4 Exceptions / 21.2
Fallback Logic: on API timeout, rate limit, missing key, or any error, the
pipeline proceeds using YOLOv11 output alone with the overall confidence
capped at settings.gemini_fallback_confidence_cap. This is a normal,
expected runtime path in this environment (no GEMINI_API_KEY configured,
no outbound network) - not an error surfaced to the caller.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from app.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class GeminiResult:
    used: bool
    description: str | None
    agrees_with_top_candidate: bool | None
    fallback_reason: str | None


async def cross_validate(
    image_bytes: bytes,
    top_candidate_class: str | None,
    candidate_classes: list[str],
    citizen_description: str | None,
) -> GeminiResult:
    settings = get_settings()

    if not settings.gemini_api_key:
        return GeminiResult(
            used=False,
            description=None,
            agrees_with_top_candidate=None,
            fallback_reason="GEMINI_API_KEY not configured",
        )

    if top_candidate_class is None:
        # Nothing for Gemini to cross-validate against (SRS 21.1 fallback
        # already routes this straight to manual review) - skip the call
        # rather than spending an API request with no candidate to check.
        return GeminiResult(
            used=False,
            description=None,
            agrees_with_top_candidate=None,
            fallback_reason="no YOLOv11 candidate to cross-validate",
        )

    try:
        return await asyncio.wait_for(
            _call_gemini(image_bytes, top_candidate_class, candidate_classes, citizen_description),
            timeout=settings.gemini_timeout_seconds,
        )
    except asyncio.TimeoutError:
        logger.warning("Gemini API call timed out after %ss", settings.gemini_timeout_seconds)
        return GeminiResult(
            used=False, description=None, agrees_with_top_candidate=None,
            fallback_reason="timeout",
        )
    except Exception as exc:
        # Covers rate limiting (SRS 15.4 Exceptions names this explicitly)
        # and any other API error - all fall back the same way per SRS.
        logger.warning("Gemini API call failed, falling back to YOLOv11-only: %s", exc)
        return GeminiResult(
            used=False, description=None, agrees_with_top_candidate=None,
            fallback_reason=f"api_error: {exc}",
        )


async def _call_gemini(
    image_bytes: bytes,
    top_candidate_class: str,
    candidate_classes: list[str],
    citizen_description: str | None,
) -> GeminiResult:
    """
    Real Gemini call. Never exercised in this environment (no network, no
    API key configured by default) - correctness here is reviewed
    manually, same NOT VERIFIED discipline as the rest of this project.
    """
    settings = get_settings()
    import io

    import google.generativeai as genai  # imported lazily - optional at runtime
    from PIL import Image

    genai.configure(api_key=settings.gemini_api_key)
    model = genai.GenerativeModel(settings.gemini_model_name)

    prompt = _build_prompt(top_candidate_class, candidate_classes, citizen_description)
    pil_image = Image.open(io.BytesIO(image_bytes))
    response = await asyncio.to_thread(model.generate_content, [pil_image, prompt])

    text = (getattr(response, "text", None) or "").strip()
    agrees = top_candidate_class.lower() in text.lower() if text else None
    return GeminiResult(
        used=True,
        description=text or None,
        agrees_with_top_candidate=agrees,
        fallback_reason=None,
    )


def _build_prompt(
    top_candidate_class: str, candidate_classes: list[str], citizen_description: str | None
) -> str:
    lines = [
        "You are validating an automated civic-issue classification for a "
        "municipal complaint platform.",
        f"The computer-vision model's top candidate category is: {top_candidate_class}.",
    ]
    if len(candidate_classes) > 1:
        lines.append(f"Other candidate categories considered: {', '.join(candidate_classes[1:])}.")
    if citizen_description:
        lines.append(f"The citizen's own description of the issue: \"{citizen_description}\"")
    lines.append(
        "Based on the image, confirm or revise the classification and write a "
        "one-to-two sentence, plain-language description of the issue suitable "
        "for a government officer and the reporting citizen to both read."
    )
    return "\n".join(lines)
