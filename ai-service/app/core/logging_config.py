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

import logging
import sys

AUDIT_LOGGER_NAME = "app.audit"


def configure_logging() -> None:
    root = logging.getLogger()
    if root.handlers:
        # Avoid duplicate handlers on reload (uvicorn --reload re-imports).
        return

    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            fmt="%(asctime)s %(levelname)s [%(name)s] %(message)s",
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
