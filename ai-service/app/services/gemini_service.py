"""
Gemini API contextual reasoning wrapper (SRS 15.4 Features, 21.2).

Cross-validates the YOLOv11 classification using broader scene context and
generates a human-readable description. Per SRS 15.4 Exceptions / 21.2
Fallback Logic: on API timeout, rate limit, missing key, or any error, the
pipeline proceeds using YOLOv11 output alone with the overall confidence
capped at settings.gemini_fallback_confidence_cap. This is a normal,
expected runtime path in this environment (no GEMINI_API_KEY configured,
no outbound network) - not an error surfaced to the caller.

Audit GAP-007: migrated from the end-of-life google-generativeai SDK to the
supported google-genai SDK (``from google import genai``), and the default
model to a current Flash model (config.gemini_model_name). Audit GAP-053:
Gemini is also asked which supported category the image shows, so a
disagreement yields a revised classification (SRS 21.2 "confirmed or revised
classification") instead of only a lower confidence.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass

from app.config import get_settings

logger = logging.getLogger(__name__)


# Audit GAP-053: the categories Gemini may answer with (IssueCategory values).
SUPPORTED_CATEGORIES = (
    "POTHOLE", "GARBAGE_OVERFLOW", "WATER_LEAKAGE", "BROKEN_STREET_LIGHT",
    "OPEN_MANHOLE", "ILLEGAL_CONSTRUCTION", "GENERAL",
)


@dataclass
class GeminiResult:
    used: bool
    description: str | None
    agrees_with_top_candidate: bool | None
    fallback_reason: str | None
    # Audit GAP-053: Gemini's own category (one of SUPPORTED_CATEGORIES) or None.
    suggested_category: str | None = None


# Audit GAP-007: outcome of the most recent real Gemini attempt, for /health.
# Updated only by actual calls - /health never makes a request of its own.
_last_call: dict = {"at": None, "used": None, "fallback_reason": None}


def last_call_status() -> dict:
    return dict(_last_call)


def _record(result: "GeminiResult") -> "GeminiResult":
    import datetime as _dt
    _last_call.update(
        at=_dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        used=result.used,
        fallback_reason=result.fallback_reason,
    )
    return result


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
        return _record(await asyncio.wait_for(
            _call_with_retry(image_bytes, top_candidate_class, candidate_classes, citizen_description),
            timeout=settings.gemini_timeout_seconds,
        ))
    except asyncio.TimeoutError:
        logger.warning("Gemini API call timed out after %ss", settings.gemini_timeout_seconds)
        return _record(GeminiResult(
            used=False, description=None, agrees_with_top_candidate=None,
            fallback_reason="timeout",
        ))
    except Exception as exc:
        # Covers rate limiting (SRS 15.4 Exceptions names this explicitly)
        # and any other API error - all fall back the same way per SRS.
        # Remaining-gaps item 2: only the exception TYPE goes into the response
        # (it is persisted in predictions.raw_model_output); the logged message
        # is redacted of the API key. Previously the raw SDK message was
        # returned and logged verbatim.
        logger.warning("Gemini API call failed, falling back to YOLOv11-only: %s: %s",
                       type(exc).__name__, redact_secret(str(exc), settings.gemini_api_key))
        return _record(GeminiResult(
            used=False, description=None, agrees_with_top_candidate=None,
            fallback_reason=f"api_error: {type(exc).__name__}",
        ))


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

    # Audit GAP-007: google-genai (the supported SDK), imported lazily so the
    # service starts without it. HttpOptions.timeout is in MILLISECONDS and
    # bounds the underlying HTTP request itself (asyncio.wait_for in the caller
    # only bounds the await).
    from google import genai
    from google.genai import types

    client = genai.Client(
        api_key=settings.gemini_api_key,
        http_options=types.HttpOptions(timeout=int(settings.gemini_timeout_seconds * 1000)),
    )
    prompt = _build_prompt(top_candidate_class, candidate_classes, citizen_description)
    response = await client.aio.models.generate_content(
        model=settings.gemini_model_name,
        contents=[
            types.Part.from_bytes(data=image_bytes, mime_type=_sniff_mime_type(image_bytes)),
            prompt,
        ],
        config=types.GenerateContentConfig(response_mime_type="application/json"),
    )

    text = (getattr(response, "text", None) or "").strip()
    agrees, description, category = parse_gemini_reply_full(text)
    return GeminiResult(
        used=True,
        description=description,
        agrees_with_top_candidate=agrees,
        fallback_reason=None,
        suggested_category=category,
    )


def _sniff_mime_type(image_bytes: bytes) -> str:
    """The upload's real type (JPEG/PNG/WEBP are what the backend accepts)."""
    if image_bytes[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if image_bytes[:4] == b"RIFF" and image_bytes[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def parse_gemini_reply(text: str) -> tuple[bool | None, str | None]:
    """Backward-compatible (agrees, description) view of parse_gemini_reply_full."""
    agrees, description, _category = parse_gemini_reply_full(text)
    return agrees, description


def parse_gemini_reply_full(text: str) -> tuple[bool | None, str | None, str | None]:
    """Remaining-gaps item 2: read the structured verdict the prompt asks for.

    The previous check (``top_candidate in reply``) counted a reply such as
    "this is not a pothole" as AGREEING with "pothole". Now agreement is only
    taken from an explicit boolean "agrees" field in a JSON reply; anything
    else leaves agreement unknown (None) - never guessed - and keeps the text
    as the description.
    """
    if not text:
        return None, None, None
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
        return None, text, None
    if not isinstance(data, dict):
        return None, text, None
    agrees = data.get("agrees") if isinstance(data.get("agrees"), bool) else None
    description = data.get("description")
    description = description.strip() if isinstance(description, str) and description.strip() else text
    # Audit GAP-053: only an exact supported category is accepted - never guessed.
    category = data.get("category")
    category = category.strip().upper() if isinstance(category, str) else None
    if category not in SUPPORTED_CATEGORIES:
        category = None
    return agrees, description, category


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
        "If the image shows a different civic issue, say which one. Allowed categories: "
        + ", ".join(SUPPORTED_CATEGORIES) + "."
    )
    lines.append(
        'Reply with ONLY a JSON object, no other text: {"agrees": true or false '
        '(does the image show the top candidate category?), "category": one of the allowed '
        'categories (the category the image actually shows), "description": "..."}'
    )
    return "\n".join(lines)
