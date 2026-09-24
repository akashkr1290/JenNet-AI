"""
Gap-backlog Patch 2 (Sep 2026 audit): generates models/registry.json's
entry for a given weights file by reading real data out of the checkpoint
itself (Ultralytics stores training args/metrics/epoch/date inside the
.pt file) plus a computed SHA256 hash - nothing in this script's output
is fabricated or guessed; every field is either read from the checkpoint
or computed from the file's actual bytes.

Usage (from ai-service/):
    python scripts/generate_model_manifest.py models/yolov11-civic-v1.0.pt

Prints a JSON object to stdout - append it to models/registry.json's
"versions" array by hand (deliberately not auto-written: a human should
review/commit a new version entry, not have it silently appended).
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(weights_path: Path) -> dict:
    import torch  # local import: only needed when this script actually runs

    checkpoint = torch.load(weights_path, map_location="cpu", weights_only=False)
    train_args = checkpoint.get("train_args") or {}
    train_metrics = checkpoint.get("train_metrics") or {}

    # class_names: prefer the model's own head, fall back to the raw
    # checkpoint's "names" key (both are how Ultralytics stores this,
    # depending on export path) - never EXPECTED_CLASSES (that constant is
    # documentation of the *target* taxonomy, not a runtime source of
    # truth - see yolo_service.py's own comment on it).
    model_obj = checkpoint.get("model")
    class_names = None
    if model_obj is not None and hasattr(model_obj, "names"):
        class_names = model_obj.names
    elif "names" in checkpoint:
        class_names = checkpoint["names"]

    return {
        "file_name": weights_path.name,
        "sha256": sha256_of(weights_path),
        "file_size_bytes": weights_path.stat().st_size,
        "base_model": train_args.get("model"),
        "dataset": train_args.get("data"),
        "epochs_configured": train_args.get("epochs"),
        "epoch_reached": checkpoint.get("epoch"),
        "image_size": train_args.get("imgsz"),
        "date_trained": checkpoint.get("date"),
        "ultralytics_version": checkpoint.get("version"),
        "class_names": class_names,
        "metrics": {
            "precision": train_metrics.get("metrics/precision(B)"),
            "recall": train_metrics.get("metrics/recall(B)"),
            "mAP50": train_metrics.get("metrics/mAP50(B)"),
            "mAP50_95": train_metrics.get("metrics/mAP50-95(B)"),
        },
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python scripts/generate_model_manifest.py <path-to-weights.pt>", file=sys.stderr)
        sys.exit(1)
    manifest = build_manifest(Path(sys.argv[1]))
    print(json.dumps(manifest, indent=2, default=str))
