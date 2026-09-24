"""Shared response schemas - the SRS 20.6 error envelope, for OpenAPI docs."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ErrorResponse(BaseModel):
    error_code: str = Field(..., examples=["UNPROCESSABLE_IMAGE"])
    message: str
    details: Any | None = None
