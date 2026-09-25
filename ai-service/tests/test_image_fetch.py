"""
Phase 20: unit tests for app/core/image_fetch.py - the image_url/
image_base64 dual-input resolution (see that module's docstring for why
both exist). Focuses on the parts reachable without a real network call
(_decode_base64, and fetch_image_bytes's own input-combination validation);
the httpx.AsyncClient network path (_fetch_from_url) is exercised only for
its input-validation branches, since this sandboxed environment has no
route to an arbitrary external image host to genuinely fetch from (an
honest NOT VERIFIED for that one branch - see PROJECT_PROGRESS.md's Phase
20 TESTS section).

Actually executed this phase.
"""
from __future__ import annotations

import asyncio
import base64

import pytest

from app.core.exceptions import InvalidRequestError
from app.core.image_fetch import fetch_image_bytes


def _run(coro):
    return asyncio.run(coro)


class TestFetchImageBytesInputValidation:
    def test_both_sources_provided_raises_invalid_request(self):
        with pytest.raises(InvalidRequestError):
            _run(fetch_image_bytes("https://example.com/a.jpg", "aGVsbG8="))

    def test_neither_source_provided_raises_invalid_request(self):
        with pytest.raises(InvalidRequestError):
            _run(fetch_image_bytes(None, None))

    def test_base64_only_decodes_successfully(self):
        payload = base64.b64encode(b"fake-image-bytes").decode()
        result = _run(fetch_image_bytes(None, payload))
        assert result == b"fake-image-bytes"


class TestDecodeBase64:
    def test_invalid_base64_raises_invalid_request(self):
        with pytest.raises(InvalidRequestError):
            _run(fetch_image_bytes(None, "not-valid-base64!!!"))

    def test_data_url_prefix_is_stripped(self):
        raw = b"\x89PNG-fake-bytes"
        encoded = base64.b64encode(raw).decode()
        data_url = f"data:image/png;base64,{encoded}"
        result = _run(fetch_image_bytes(None, data_url))
        assert result == raw

    def test_oversized_decoded_payload_is_rejected(self):
        from app.config import get_settings

        settings = get_settings()
        # One byte over the configured max, after base64 decoding.
        oversized_raw = b"x" * (settings.max_image_bytes + 1)
        encoded = base64.b64encode(oversized_raw).decode()
        with pytest.raises(InvalidRequestError):
            _run(fetch_image_bytes(None, encoded))


class TestImageUrlSsrfGuard:
    """Audit GAP-035: image_url must not let callers make the ai-service fetch
    arbitrary (e.g. internal) URLs."""

    def _settings(self, hosts="", allow_http=False):
        from app.config import Settings

        return Settings(image_url_allowed_hosts=hosts, image_url_allow_http=allow_http)

    def test_image_url_is_disabled_by_default(self):
        from app.core.image_fetch import _check_url_allowed

        with pytest.raises(InvalidRequestError, match="disabled"):
            _check_url_allowed("https://169.254.169.254/latest/meta-data/", self._settings())

    def test_internal_host_not_in_allow_list_is_rejected(self):
        from app.core.image_fetch import _check_url_allowed

        with pytest.raises(InvalidRequestError, match="not in IMAGE_URL_ALLOWED_HOSTS"):
            _check_url_allowed("https://127.0.0.1:8001/health", self._settings("media.example.org"))

    def test_plain_http_is_rejected_unless_explicitly_allowed(self):
        from app.core.image_fetch import _check_url_allowed

        with pytest.raises(InvalidRequestError):
            _check_url_allowed("http://media.example.org/a.jpg", self._settings("media.example.org"))
        _check_url_allowed("http://media.example.org/a.jpg", self._settings("media.example.org", allow_http=True))

    def test_allow_listed_https_host_passes_the_guard(self):
        from app.core.image_fetch import _check_url_allowed

        _check_url_allowed("https://Media.Example.org/complaints/1.jpg", self._settings("media.example.org"))

    def test_fetch_of_internal_url_is_refused_before_any_network_call(self):
        # With default settings the request is rejected before httpx is used.
        with pytest.raises(InvalidRequestError):
            _run(fetch_image_bytes("http://127.0.0.1:8001/health", None))
