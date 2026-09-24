"""
Custom exceptions and the FastAPI exception handlers that translate them
into the error envelope SRS 20.6 specifies for this service:

    { "error_code": str, "message": str, "details": object|null }

This is a deliberately different shape from the Spring Boot backend's own
locked error envelope (PROJECT_INTEGRATION.md Section 2:
{timestamp, status, error, message, path, details}). That shape was locked
in Phase 3 specifically for the Java backend's GlobalExceptionHandler; this
service is a separate Python component and SRS 20.6 ("Common API
Conventions") gives its own literal envelope shape, which this module
follows exactly. Recorded as a decision in PROJECT_INTEGRATION.md Section 6
so Phase 8 (backend<->ai-service integration) translates rather than
assumes a shared shape.
"""
from __future__ import annotations

import logging

from typing import Any

from fastapi.exceptions import RequestValidationError
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class AiServiceError(Exception):
    """Base class for all handled AI Service errors."""

    status_code: int = 500
    error_code: str = "AI_SERVICE_ERROR"

    def __init__(self, message: str, details: Any = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details


class UnprocessableImageError(AiServiceError):
    """
    Image failed the OpenCV quality gate (too blurry / too small / too dark)
    before any model inference was attempted - SRS 21.3 fallback logic.
    Maps to 422 per SRS 20.3's documented response codes for /ai/classify.
    """

    status_code = 422
    error_code = "UNPROCESSABLE_IMAGE"


class ImageFetchError(AiServiceError):
    """image_url could not be retrieved (network error, 404, wrong content-type)."""

    status_code = 422
    error_code = "IMAGE_FETCH_FAILED"


class ModelTimeoutError(AiServiceError):
    """
    A model call (YOLOv11 inference) exceeded its configured timeout.
    Maps to 504 per SRS 20.3's documented response codes for /ai/classify.
    Note: Gemini timeout is NOT this - Gemini timeout is a documented
    fallback path (SRS 15.4 Exceptions), not an error response; see
    services/gemini_service.py.
    """

    status_code = 504
    error_code = "MODEL_TIMEOUT"


class InvalidRequestError(AiServiceError):
    status_code = 400
    error_code = "INVALID_REQUEST"


class UnauthorizedError(AiServiceError):
    status_code = 401
    error_code = "UNAUTHORIZED"


_log = logging.getLogger(__name__)


def _safe_validation_errors(exc: RequestValidationError) -> list[dict]:
    """Field location, message and type only - never the offending input.

    Gap-backlog final recheck (Sep 2026): FastAPI's default handler echoes
    each error's raw ``input`` back through jsonable_encoder. For a
    non-JSON binary body (e.g. an image posted as multipart instead of the
    documented JSON contract) that input is non-UTF-8 bytes, the encoder
    raises UnicodeDecodeError, and the catch-all below turned a plain 422
    client error into a 500 - found by a live uvicorn run, not by the
    TestClient suite, which never posted binary to this path.
    """
    safe = []
    for err in exc.errors():
        safe.append({
            "loc": [str(part) for part in err.get("loc", ())],
            "msg": str(err.get("msg", "")),
            "type": str(err.get("type", "")),
        })
    return safe


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "error_code": "VALIDATION_ERROR",
                "message": "Request body failed validation (this API expects a JSON body).",
                "details": _safe_validation_errors(exc),
            },
        )

    @app.exception_handler(AiServiceError)
    async def handle_ai_service_error(request: Request, exc: AiServiceError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error_code": exc.error_code,
                "message": exc.message,
                "details": exc.details,
            },
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        # Never leak raw tracebacks/internal messages to the caller - log
        # server-side (logging_config wires uvicorn/app loggers) and return
        # a generic envelope instead.
        # (Final recheck: the comment above promised server-side logging but
        # nothing was logged, so unexpected errors left no trace at all.)
        _log.exception("Unhandled error on %s %s", request.method, request.url.path)
        return JSONResponse(
            status_code=500,
            content={
                "error_code": "INTERNAL_ERROR",
                "message": "An unexpected error occurred while processing the request.",
                "details": None,
            },
        )
