"""Live pilot finding (2026-09-27): every Gemini call failed with
"400 INVALID_ARGUMENT ... Manually set deadline 8s is too short. Minimum
allowed deadline is 10s." The request deadline must never go below 10 s."""
import asyncio

from app.config import Settings, get_settings
from app.services import gemini_service as gs


def test_default_timeout_is_accepted_by_the_api():
    assert Settings().gemini_timeout_seconds >= 10


def test_http_deadline_never_below_the_api_minimum():
    assert gs.http_timeout_ms(8.0) == 10_000
    assert gs.http_timeout_ms(0.05) == 10_000
    assert gs.http_timeout_ms(15.0) == 15_000


def test_client_gets_at_least_ten_seconds_even_if_configured_lower(monkeypatch):
    from google import genai

    seen = {}

    class FakeModels:
        async def generate_content(self, *, model, contents, config=None):
            seen["afc_disabled"] = config.automatic_function_calling.disable
            return type("R", (), {"text": '{"category": "POTHOLE", "description": "A pothole."}'})()

    class FakeClient:
        def __init__(self, *, api_key, http_options):
            seen["timeout"] = http_options.timeout
            self.aio = type("Aio", (), {"models": FakeModels()})()

    s = get_settings()
    monkeypatch.setattr(s, "gemini_api_key", "test-key-not-real")
    monkeypatch.setattr(s, "gemini_timeout_seconds", 8.0)
    monkeypatch.setattr(genai, "Client", FakeClient)
    result = asyncio.run(gs._call_gemini(b"\x89PNG\r\n\x1a\n" + b"0" * 16, None, [], None))
    assert seen["timeout"] == 10_000 and seen["afc_disabled"] is True
    assert result.used is True and result.suggested_category == "POTHOLE"
