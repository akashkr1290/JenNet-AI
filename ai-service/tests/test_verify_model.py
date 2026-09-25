"""Audit GAP-060: the model verification gate used by Docker builds and CI."""
from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest

from scripts.verify_model import LFS_POINTER_PREFIX, verify

MODELS = Path(__file__).resolve().parent.parent / "models"


def _registry(tmp_path: Path, payload: bytes) -> Path:
    (tmp_path / "m.pt").write_bytes(payload)
    (tmp_path / "registry.json").write_text(json.dumps({
        "active": "v1",
        "versions": [{"model_version": "v1", "manifest": {"file_name": "m.pt",
                                                          "sha256": hashlib.sha256(b"real-weights").hexdigest()}}],
    }))
    return tmp_path


def test_matching_file_passes(tmp_path):
    ok, msg = verify(_registry(tmp_path, b"real-weights"))
    assert ok, msg


def test_missing_file_fails_clearly(tmp_path):
    d = _registry(tmp_path, b"x")
    (d / "m.pt").unlink()
    ok, msg = verify(d)
    assert not ok and "REQUIRED MODEL MISSING" in msg


def test_lfs_pointer_is_rejected(tmp_path):
    ok, msg = verify(_registry(tmp_path, LFS_POINTER_PREFIX + b"v1\noid sha256:abc\nsize 1\n"))
    assert not ok and "Git LFS pointer" in msg


def test_checksum_mismatch_is_rejected(tmp_path):
    ok, msg = verify(_registry(tmp_path, b"tampered"))
    assert not ok and "CHECKSUM MISMATCH" in msg


@pytest.mark.skipif(not (MODELS / "yolov11-civic-v1.0.pt").is_file(), reason="real weights not present")
def test_shipped_registry_and_real_weights_match():
    ok, msg = verify(MODELS)
    assert ok, msg


def test_require_model_startup_gate_refuses_to_start_without_weights(monkeypatch, tmp_path):
    import asyncio

    import app.main as main_module
    from app.config import Settings
    from app.services import yolo_service as ys

    settings = Settings(require_model=True, yolo_model_path=str(tmp_path / "missing.pt"))
    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    monkeypatch.setattr(ys, "get_settings", lambda: settings)
    monkeypatch.setattr(ys, "_service_singleton", None)
    with pytest.raises(RuntimeError, match="REQUIRE_MODEL"):
        asyncio.run(main_module.on_startup())
    monkeypatch.setattr(ys, "_service_singleton", None)
