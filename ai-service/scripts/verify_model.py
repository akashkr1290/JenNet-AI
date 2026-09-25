"""Audit GAP-060: fail loudly when the trained YOLO weights are not present.

The weights (models/yolov11-civic-v1.0.pt, 19 MB) are distributed through
Git LFS (see .gitattributes). A clone made without LFS - or with the file
missing - would otherwise start an ai-service that silently reports
MODEL_UNAVAILABLE and routes every complaint to manual review.

This script checks the ACTIVE model named in models/registry.json:
  * the file exists,
  * it is not an un-fetched Git LFS pointer file,
  * its SHA-256 equals the checksum recorded in the registry manifest.

Exit code 0 = OK, 1 = problem (message printed to stderr). Used by
docker/Dockerfile.ai-service (build fails) and .github/workflows/*.yml.

Usage: python scripts/verify_model.py [--models-dir models]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

LFS_POINTER_PREFIX = b"version https://git-lfs.github.com/spec/"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(models_dir: Path) -> tuple[bool, str]:
    registry_path = models_dir / "registry.json"
    if not registry_path.is_file():
        return False, f"{registry_path} not found"
    registry = json.loads(registry_path.read_text())
    active = registry.get("active")
    entry = next((v for v in registry.get("versions", []) if v.get("model_version") == active), None)
    if entry is None:
        return False, f"registry.json 'active' version {active!r} has no entry in 'versions'"
    manifest = entry.get("manifest", {})
    file_name = manifest.get("file_name") or entry.get("file_name")
    expected = manifest.get("sha256")
    if not file_name or not expected:
        return False, f"registry entry {active!r} lacks file_name/sha256"
    model_path = models_dir / file_name
    if not model_path.is_file():
        return False, (
            f"REQUIRED MODEL MISSING: {model_path} does not exist. The trained weights are stored "
            "with Git LFS - run `git lfs install && git lfs pull` (CI: actions/checkout with "
            "`lfs: true`). A model must never be fabricated or substituted."
        )
    with model_path.open("rb") as handle:
        head = handle.read(len(LFS_POINTER_PREFIX))
    if head == LFS_POINTER_PREFIX:
        return False, (
            f"REQUIRED MODEL MISSING: {model_path} is a Git LFS pointer, not the weights. "
            "Run `git lfs pull` (CI: actions/checkout with `lfs: true`)."
        )
    actual = sha256_of(model_path)
    if actual != expected:
        return False, (
            f"MODEL CHECKSUM MISMATCH for {model_path}: expected {expected} (registry.json), got {actual}."
        )
    return True, f"OK: {file_name} ({model_path.stat().st_size} bytes) matches registry sha256 for {active}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--models-dir", default=str(Path(__file__).resolve().parent.parent / "models"))
    args = parser.parse_args(argv)
    ok, message = verify(Path(args.models_dir))
    print(message, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
