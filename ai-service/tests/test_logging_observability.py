"""Audit GAP-041 (SRS 26/27): JSON logs and request/correlation ids."""
from __future__ import annotations

import json
import logging

from fastapi.testclient import TestClient

from app.core import logging_config
from app.core.logging_config import (
    JsonFormatter,
    RequestIdFilter,
    adopt_or_create_request_id,
    request_id_var,
    resolve_log_format,
)
from app.main import app


def _record(message: str, exc_info=None) -> logging.LogRecord:
    record = logging.LogRecord("app.test", logging.WARNING, __file__, 1, message, None, exc_info)
    RequestIdFilter().filter(record)
    return record


def test_json_formatter_emits_one_parseable_object_with_request_id():
    token = request_id_var.set("abc123def456")
    try:
        line = JsonFormatter().format(_record('multi\nline "quoted" message'))
    finally:
        request_id_var.reset(token)
    assert "\n" not in line
    payload = json.loads(line)
    assert payload["level"] == "WARNING"
    assert payload["service"] == "jannet-ai-service"
    assert payload["logger"] == "app.test"
    assert payload["requestId"] == "abc123def456"
    assert payload["message"] == 'multi\nline "quoted" message'
    assert payload["timestamp"].endswith("Z")


def test_json_formatter_includes_the_stack_trace():
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        line = JsonFormatter().format(_record("failed", sys.exc_info()))
    payload = json.loads(line)
    assert "ValueError: boom" in payload["stackTrace"]
    assert "requestId" not in payload  # no request in progress


def test_request_ids_are_adopted_only_when_well_formed():
    assert adopt_or_create_request_id("0123456789abcdef0123456789abcdef") == "0123456789abcdef0123456789abcdef"
    generated = adopt_or_create_request_id("bad id\nwith newline")
    assert generated != "bad id\nwith newline" and len(generated) == 32
    assert len(adopt_or_create_request_id(None)) == 32
    assert len(adopt_or_create_request_id("x" * 65)) == 32


def test_log_format_defaults_to_json_outside_local():
    assert resolve_log_format({"AI_SERVICE_ENV": "production"}) == "json"
    assert resolve_log_format({"AI_SERVICE_ENV": "local"}) == "text"
    assert resolve_log_format({}) == "text"
    assert resolve_log_format({"AI_SERVICE_ENV": "local", "LOG_FORMAT": "json"}) == "json"
    assert resolve_log_format({"AI_SERVICE_ENV": "production", "LOG_FORMAT": "TEXT"}) == "text"


def test_middleware_echoes_the_backend_request_id_and_logs_with_it(caplog):
    client = TestClient(app)
    seen = []

    class Capture(logging.Handler):
        def emit(self, record):
            seen.append(getattr(record, "request_id", None))

    handler = Capture()
    handler.addFilter(RequestIdFilter())
    logging.getLogger("app.test.middleware").addHandler(handler)

    @app.get("/__test_log")
    def _log_endpoint():  # pragma: no cover - executed through the client
        logging.getLogger("app.test.middleware").warning("inside request")
        return {"ok": True}

    try:
        response = client.get("/__test_log", headers={"X-Request-Id": "req-1234567890"})
        assert response.headers["X-Request-Id"] == "req-1234567890"
        assert seen == ["req-1234567890"]
        other = client.get("/health")
        assert len(other.headers["X-Request-Id"]) == 32
    finally:
        logging.getLogger("app.test.middleware").removeHandler(handler)
        app.router.routes[:] = [r for r in app.router.routes if getattr(r, "path", "") != "/__test_log"]
    assert request_id_var.get() is None
    assert logging_config.SERVICE_NAME == "jannet-ai-service"
