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
import json
import logging
import re
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
            _call_with_retry(image_bytes, top_candidate_class, candidate_classes, citizen_description),
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
        # Remaining-gaps item 2: only the exception TYPE goes into the response
        # (it is persisted in predictions.raw_model_output); the logged message
        # is redacted of the API key. Previously the raw SDK message was
        # returned and logged verbatim.
        logger.warning("Gemini API call failed, falling back to YOLOv11-only: %s: %s",
                       type(exc).__name__, redact_secret(str(exc), settings.gemini_api_key))
        return GeminiResult(
            used=False, description=None, agrees_with_top_candidate=None,
            fallback_reason=f"api_error: {type(exc).__name__}",
        )


_TRANSIENT_ERROR_NAMES = {
    "ResourceExhausted", "TooManyRequests", "ServiceUnavailable", "DeadlineExceeded",
    "InternalServerError", "ServerError",
}


def is_transient(exc: Exception) -> bool:
    """Rate limit / temporary unavailability - worth one bounded retry."""
    if type(exc).__name__ in _TRANSIENT_ERROR_NAMES:
        return True
    text = str(exc)
    return any(code in text for code in ("429", "503", "Resource has been exhausted"))


def redact_secret(text: str, secret: str | None) -> str:
    """Remove the API key (and any key= query parameter) from text before logging."""
    if secret:
        text = text.replace(secret, "[REDACTED]")
    return re.sub(r"(?i)(key=)[^&\s\"']+", r"\1[REDACTED]", text)


async def _call_with_retry(
    image_bytes: bytes,
    top_candidate_class: str,
    candidate_classes: list[str],
    citizen_description: str | None,
) -> GeminiResult:
    """Remaining-gaps item 2: bounded retry of TRANSIENT failures only
    (gemini_max_retries, short linear backoff). Non-transient errors fail
    fast. The caller's asyncio.wait_for still bounds the total time."""
    settings = get_settings()
    attempts = max(0, settings.gemini_max_retries) + 1
    for attempt in range(1, attempts + 1):
        try:
            return await _call_gemini(image_bytes, top_candidate_class, candidate_classes, citizen_description)
        except Exception as exc:
            if attempt >= attempts or not is_transient(exc):
                raise
            logger.info("Transient Gemini error (%s); retry %d/%d", type(exc).__name__, attempt, attempts - 1)
            await asyncio.sleep(0.5 * attempt)
    raise RuntimeError("unreachable")


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
    # Remaining-gaps item 2: bound the underlying HTTP request itself, not
    # only the awaiting coroutine (asyncio.wait_for cannot cancel a thread).
    response = await asyncio.to_thread(
        model.generate_content, [pil_image, prompt],
        request_options={"timeout": settings.gemini_timeout_seconds},
    )

    text = (getattr(response, "text", None) or "").strip()
    agrees, description = parse_gemini_reply(text)
    return GeminiResult(
        used=True,
        description=description,
        agrees_with_top_candidate=agrees,
        fallback_reason=None,
    )


def parse_gemini_reply(text: str) -> tuple[bool | None, str | None]:
    """Remaining-gaps item 2: read the structured verdict the prompt asks for.

    The previous check (``top_candidate in reply``) counted a reply such as
    "this is not a pothole" as AGREEING with "pothole". Now agreement is only
    taken from an explicit boolean "agrees" field in a JSON reply; anything
    else leaves agreement unknown (None) - never guessed - and keeps the text
    as the description.
    """
    if not text:
        return None, None
    candidate = text.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", candidate, re.S)
    if fenced:
        candidate = fenced.group(1)
    elif not candidate.startswith("{"):
        brace = re.search(r"\{.*\}", candidate, re.S)
        candidate = brace.group(0) if brace else candidate
    try:
        data = json.loads(candidate)
    except (ValueError, TypeError):
        return None, text
    if not isinstance(data, dict):
        return None, text
    agrees = data.get("agrees") if isinstance(data.get("agrees"), bool) else None
    description = data.get("description")
    description = description.strip() if isinstance(description, str) and description.strip() else text
    return agrees, description


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
    lines.append(
        'Reply with ONLY a JSON object, no other text: {"agrees": true or false '
        '(does the image show the top candidate category?), "description": "..."}'
    )
    return "\n".join(lines)
