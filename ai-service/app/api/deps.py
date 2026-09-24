"""
Internal service-to-service authentication (SRS 20.3: "Internal
service-to-service auth (API key)").

This is deliberately NOT JWT-based - the caller here is the Spring Boot
backend itself (Phase 8 will wire the actual outbound call), not an
end-user, so there is no user identity to validate. A shared, rotatable
API key checked via a header is what SRS 20.3 specifies for every
/api/v1/ai/* endpoint.
"""
from __future__ import annotations

import hmac

from fastapi import Header

from app.config import get_settings
from app.core.exceptions import UnauthorizedError

API_KEY_HEADER_NAME = "X-Internal-Api-Key"


async def require_internal_api_key(
    x_internal_api_key: str | None = Header(default=None, alias=API_KEY_HEADER_NAME),
) -> None:
    settings = get_settings()
    if not x_internal_api_key or not hmac.compare_digest(
        x_internal_api_key, settings.ai_service_api_key
    ):
        raise UnauthorizedError(
            f"Missing or invalid {API_KEY_HEADER_NAME} header."
        )
