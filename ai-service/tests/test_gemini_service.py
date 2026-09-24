"""Remaining-gaps item 2: Gemini production hardening (no network, no real key)."""
from __future__ import annotations

import asyncio
import logging

import pytest

from app.config import get_settings
from app.services import gemini_service as gs
from app.services.gemini_service import GeminiResult

SECRET = "fake-test-key-not-a-real-credential"


@pytest.fixture()
def configured(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "gemini_api_key", SECRET)
    monkeypatch.setattr(s, "gemini_timeout_seconds", 5.0)
    monkeypatch.setattr(s, "gemini_max_retries", 1)
    monkeypatch.setattr(gs.asyncio, "sleep", _no_sleep)
    return s


async def _no_sleep(_seconds):
    return None


def _run(**kw):
    return asyncio.run(gs.cross_validate(b"img", "pothole", ["pothole", "garbage_overflow"], None, **kw))


def test_no_key_falls_back_without_calling_the_api(monkeypatch):
    monkeypatch.setattr(get_settings(), "gemini_api_key", "")
    called = []
    monkeypatch.setattr(gs, "_call_gemini", lambda *a: called.append(1))
    result = _run()
    assert result.used is False and result.fallback_reason == "GEMINI_API_KEY not configured" and not called


def test_api_error_never_exposes_key_in_response_or_logs(configured, monkeypatch, caplog):
    async def boom(*_a):
        raise PermissionError(f"403 API key not valid: key={SECRET} for https://x/?key={SECRET}")
    monkeypatch.setattr(gs, "_call_gemini", boom)
    with caplog.at_level(logging.WARNING):
        result = _run()
    assert result.used is False and result.fallback_reason == "api_error: PermissionError"
    assert SECRET not in (result.fallback_reason or "") and SECRET not in caplog.text
    assert "[REDACTED]" in caplog.text


def test_transient_error_is_retried_once_then_succeeds(configured, monkeypatch):
    calls = []

    async def flaky(*_a):
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("429 Resource has been exhausted")
        return GeminiResult(used=True, description="ok", agrees_with_top_candidate=True, fallback_reason=None)
    monkeypatch.setattr(gs, "_call_gemini", flaky)
    assert _run().used is True and len(calls) == 2


def test_non_transient_error_is_not_retried(configured, monkeypatch):
    calls = []

    async def bad(*_a):
        calls.append(1)
        raise ValueError("400 invalid argument")
    monkeypatch.setattr(gs, "_call_gemini", bad)
    assert _run().fallback_reason == "api_error: ValueError" and len(calls) == 1


def test_timeout_falls_back(configured, monkeypatch):
    monkeypatch.setattr(configured, "gemini_timeout_seconds", 0.05)

    async def slow(*_a):
        await asyncio.Event().wait()  # never completes; the outer wait_for must cut it off
    monkeypatch.setattr(gs, "_call_gemini", slow)
    assert _run().fallback_reason == "timeout"


@pytest.mark.parametrize("reply, agrees, description", [
    ('{"agrees": true, "description": "A deep pothole."}', True, "A deep pothole."),
    ('```json\n{"agrees": false, "description": "Overflowing bin."}\n```', False, "Overflowing bin."),
    ('Sure! {"agrees": false, "description": "Not a pothole."}', False, "Not a pothole."),
    ("This is not a pothole, it is garbage.", None, "This is not a pothole, it is garbage."),
    ('{"agrees": "yes", "description": ""}', None, '{"agrees": "yes", "description": ""}'),
    ("", None, None),
])
def test_reply_parsing_never_guesses_agreement(reply, agrees, description):
    assert gs.parse_gemini_reply(reply) == (agrees, description)


def test_transient_classification_and_redaction_helpers():
    assert gs.is_transient(RuntimeError("503 Service Unavailable"))
    assert not gs.is_transient(ValueError("400 bad request"))
    assert gs.redact_secret(f"k={SECRET} key=abc123&x=1", SECRET) == "k=[REDACTED] key=[REDACTED]&x=1"
