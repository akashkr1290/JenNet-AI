"""
Structured logging setup.

SRS 15.4 Business Rules: "every AI decision is logged with model version,
confidence score, and raw output for auditability and future model
retraining." This module configures a dedicated logger
(`app.audit`) that `services/pipeline.py` writes one structured record to
per classification call - separate from general application/access logs so
it can be shipped/retained under different rules later (e.g. longer
retention for audit vs. debug logs) without re-plumbing.

This is application-level logging only. It does NOT persist to the
`predictions` table - that write happens on the Spring Boot side once
Phase 8 wires the backend<->ai-service call; this service has no direct
MySQL access (locked architecture: only the Spring Boot backend talks to
MySQL).
"""
from __future__ import annotations

import contextvars
import json
import logging
import os
import re
import sys
import uuid
from datetime import datetime, timezone

AUDIT_LOGGER_NAME = "app.audit"
SERVICE_NAME = "jannet-ai-service"

# Audit GAP-041 (SRS 26/27: correlation / request ID in every log line).
# Set per request by the middleware in app/main.py from the backend's
# X-Request-Id header (the backend forwards its own id), else generated.
# asyncio.to_thread copies context variables, so work moved to threads
# (Phase 04, GAP-034) logs the same id.
REQUEST_ID_HEADER = "X-Request-Id"
request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("request_id", default=None)
_ACCEPTED_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


def adopt_or_create_request_id(incoming: str | None) -> str:
    """The caller's id when well-formed (no log injection), otherwise a new one."""
    if incoming and _ACCEPTED_REQUEST_ID.match(incoming):
        return incoming
    return uuid.uuid4().hex


class RequestIdFilter(logging.Filter):
    """Adds ``record.request_id`` (or "-") to every record."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get() or "-"
        return True


class JsonFormatter(logging.Formatter):
    """Audit GAP-041 (SRS 27): one JSON object per line - timestamp, level,
    service, logger, requestId, message and, when present, the stack trace."""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "service": SERVICE_NAME,
            "logger": record.name,
            "thread": record.threadName,
        }
        request_id = getattr(record, "request_id", None)
        if request_id and request_id != "-":
            payload["requestId"] = request_id
        payload["message"] = record.getMessage()
        if record.exc_info:
            payload["stackTrace"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def resolve_log_format(env: dict[str, str] | None = None) -> str:
    """LOG_FORMAT=json|text; default json outside local development
    (AI_SERVICE_ENV != local), text for local development."""
    env = os.environ if env is None else env
    explicit = (env.get("LOG_FORMAT") or "").strip().lower()
    if explicit in ("json", "text"):
        return explicit
    return "text" if (env.get("AI_SERVICE_ENV") or "local").strip().lower() == "local" else "json"


def configure_logging(log_format: str | None = None) -> None:
    root = logging.getLogger()
    if root.handlers:
        # Avoid duplicate handlers on reload (uvicorn --reload re-imports).
        return

    handler = logging.StreamHandler(stream=sys.stdout)
    handler.addFilter(RequestIdFilter())
    if (log_format or resolve_log_format()) == "json":
        handler.setFormatter(JsonFormatter())
        # uvicorn installs its own text handlers (server + access log) before
        # it imports the app; route those records through the JSON handler too.
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
            uvicorn_logger = logging.getLogger(name)
            uvicorn_logger.handlers = []
            uvicorn_logger.propagate = True
    else:
        handler.setFormatter(
            logging.Formatter(
                fmt="%(asctime)s %(levelname)s [%(name)s] [%(request_id)s] %(message)s",
                datefmt="%Y-%m-%dT%H:%M:%S%z",
            )
        )
    root.addHandler(handler)
    root.setLevel(logging.INFO)

    # The audit logger is intentionally kept at INFO regardless of root
    # level changes elsewhere, since audit records must never be silently
    # dropped by a debug/warn level change made for unrelated reasons.
    logging.getLogger(AUDIT_LOGGER_NAME).setLevel(logging.INFO)


def get_audit_logger() -> logging.Logger:
    return logging.getLogger(AUDIT_LOGGER_NAME)
