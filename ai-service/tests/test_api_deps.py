"""
Phase 20: direct unit tests for app/api/deps.py's require_internal_api_key,
independent of the full HTTP stack (test_api_contract.py covers the
route-level integration). Isolates the hmac.compare_digest logic itself.

Actually executed this phase.
"""
from __future__ import annotations

import asyncio

import pytest

from app.api.deps import require_internal_api_key
from app.config import get_settings
from app.core.exceptions import UnauthorizedError


def _run(coro):
    return asyncio.run(coro)


class TestRequireInternalApiKey:
    def test_none_header_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError):
            _run(require_internal_api_key(x_internal_api_key=None))

    def test_empty_string_header_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError):
            _run(require_internal_api_key(x_internal_api_key=""))

    def test_wrong_key_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError):
            _run(require_internal_api_key(x_internal_api_key="wrong-key-entirely"))

    def test_correct_key_returns_none_without_raising(self):
        correct_key = get_settings().ai_service_api_key
        result = _run(require_internal_api_key(x_internal_api_key=correct_key))
        assert result is None

    def test_error_message_names_the_expected_header(self):
        try:
            _run(require_internal_api_key(x_internal_api_key=None))
            pytest.fail("expected UnauthorizedError")
        except UnauthorizedError as exc:
            assert "X-Internal-Api-Key" in exc.message
