"""
Acquire the raw image bytes the classification pipeline will process.

SRS 20.3's literal request contract for POST /api/v1/ai/classify is
`{ image_url }` - the backend is expected to hand this service a fetchable
reference to an already-stored image, matching the "AI Service" actor
description (SRS 13.x): a service that reads complaint records, not one
citizens/backend clients upload files to directly.

ADDITION beyond the literal SRS contract: this service also accepts an
optional `image_base64` field as an alternate input. This is necessary
because, as of Phase 6 (see PROJECT_INTEGRATION.md Section 5 / "Media
Storage Contract"), the backend's LocalStorageService issues no public or
pre-signed URL for stored photos - ImageResponse deliberately omits
storageKey - and the real backend<->ai-service integration is Phase 8, not
this phase. Without an alternate input path, this service could never be
exercised end-to-end (even manually via curl/Postman) until Phase 8 lands.
Both paths converge on the same downstream pipeline and response contract;
`image_base64` is a Phase 7-only testing convenience, not a contract change
Phase 8 is required to use. Recorded in PROJECT_INTEGRATION.md Section 6.
"""
from __future__ import annotations

import base64
import binascii

import httpx

from app.config import get_settings
from app.core.exceptions import ImageFetchError, InvalidRequestError

_ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}


async def fetch_image_bytes(image_url: str | None, image_base64: str | None) -> bytes:
    if image_url and image_base64:
        raise InvalidRequestError(
            "Provide exactly one of image_url or image_base64, not both."
        )
    if image_url:
        return await _fetch_from_url(image_url)
    if image_base64:
        return _decode_base64(image_base64)
    raise InvalidRequestError("One of image_url or image_base64 is required.")


async def _fetch_from_url(image_url: str) -> bytes:
    settings = get_settings()
    try:
        async with httpx.AsyncClient(
            timeout=settings.image_fetch_timeout_seconds, follow_redirects=True
        ) as client:
            response = await client.get(image_url)
    except httpx.RequestError as exc:
        raise ImageFetchError(f"Could not reach image_url: {exc}") from exc

    if response.status_code != 200:
        raise ImageFetchError(
            f"image_url returned HTTP {response.status_code}, expected 200."
        )

    content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
    if content_type and content_type not in _ALLOWED_CONTENT_TYPES:
        raise ImageFetchError(
            f"image_url content-type '{content_type}' is not an accepted image type."
        )

    body = response.content
    if len(body) > settings.max_image_bytes:
        raise ImageFetchError(
            f"Fetched image ({len(body)} bytes) exceeds max_image_bytes "
            f"({settings.max_image_bytes})."
        )
    return body


def _decode_base64(image_base64: str) -> bytes:
    settings = get_settings()
    try:
        # Tolerate an optional data: URL prefix (e.g. "data:image/jpeg;base64,...").
        payload = image_base64.split(",", 1)[-1] if image_base64.startswith("data:") else image_base64
        body = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidRequestError(f"image_base64 is not valid base64: {exc}") from exc

    if len(body) > settings.max_image_bytes:
        raise InvalidRequestError(
            f"Decoded image ({len(body)} bytes) exceeds max_image_bytes "
            f"({settings.max_image_bytes})."
        )
    return body
