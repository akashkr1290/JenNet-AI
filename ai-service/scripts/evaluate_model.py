#!/usr/bin/env python3
"""Reproducible YOLO model evaluation - remaining-gaps item 1.

Produces a JSON report that is explicitly a TEST-SET evaluation and keeps it
separate from the checkpoint's own training-run validation metrics (which are
copied in only as labelled reference). Nothing is estimated or invented:
without a labelled dataset there are no accuracy numbers, only timing.

  # per-class test-set metrics (precision, recall, F1, mAP50, mAP50-95)
  python scripts/evaluate_model.py --data /datasets/civic/dataset.yaml --out eval.json

  # difficult-condition subsets, each its own labelled YOLO dataset yaml
  python scripts/evaluate_model.py --data test.yaml \
      --condition low_light=/datasets/low_light.yaml --condition rain=/datasets/rain.yaml

  # inference latency only (any folder of photos, no labels needed)
  python scripts/evaluate_model.py --timing-images /photos --runs 3

--model defaults to the registry's active model. The dataset yaml follows the
standard Ultralytics format and must use the model's class names/order.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_registry  # noqa: E402

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def _metrics(result) -> tuple[dict, dict]:
    box = result.box
    names = result.names
    per_class = {}
    for i, cls_idx in enumerate(box.ap_class_index):
        p, r = float(box.p[i]), float(box.r[i])
        per_class[names[int(cls_idx)]] = {
            "precision": round(p, 5), "recall": round(r, 5),
            "f1": round(2 * p * r / (p + r), 5) if (p + r) else 0.0,
            "map50": round(float(box.ap50[i]), 5), "map50_95": round(float(box.ap[i]), 5),
        }
    mp, mr = float(box.mp), float(box.mr)
    overall = {"precision": round(mp, 5), "recall": round(mr, 5),
               "f1": round(2 * mp * mr / (mp + mr), 5) if (mp + mr) else 0.0,
               "map50": round(float(box.map50), 5), "map50_95": round(float(box.map), 5)}
    return overall, per_class


def evaluate_dataset(model_path: Path, data_yaml: Path, split: str = "test", imgsz: int = 640,
                     device: str = "cpu") -> dict:
    from ultralytics import YOLO
    model = YOLO(str(model_path))
    result = model.val(data=str(data_yaml), split=split, imgsz=imgsz, device=device, plots=False,
                       verbose=False, save_json=False)
    overall, per_class = _metrics(result)
    return {"dataset_yaml": str(data_yaml), "dataset_sha256": model_registry.sha256_of(data_yaml),
            "split": split, "overall": overall, "per_class": per_class}


def measure_latency(model_path: Path, images_dir: Path, runs: int = 1, imgsz: int = 640,
                    device: str = "cpu") -> dict:
    from ultralytics import YOLO
    images = sorted(p for p in Path(images_dir).iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
    if not images:
        raise SystemExit(f"no images found in {images_dir}")
    model = YOLO(str(model_path))
    model.predict(str(images[0]), imgsz=imgsz, device=device, verbose=False)  # warm-up, not timed
    samples = []
    for _ in range(max(1, runs)):
        for img in images:
            started = time.perf_counter()
            model.predict(str(img), imgsz=imgsz, device=device, verbose=False)
            samples.append((time.perf_counter() - started) * 1000)
    samples.sort()
    p95 = samples[max(0, int(round(0.95 * len(samples))) - 1)]
    return {"images": len(images), "samples": len(samples), "device": device,
            "mean_ms": round(statistics.fmean(samples), 1), "p50_ms": round(statistics.median(samples), 1),
            "p95_ms": round(p95, 1), "note": "model.predict wall time per image incl. pre/post-processing; "
                                             "hardware-dependent - record the machine with the result"}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--registry", type=Path, default=model_registry.DEFAULT_REGISTRY)
    ap.add_argument("--model", type=Path, help="defaults to the registry's active model")
    ap.add_argument("--data", type=Path, help="labelled YOLO dataset yaml (test split)")
    ap.add_argument("--split", default="test")
    ap.add_argument("--condition", action="append", default=[], metavar="NAME=YAML")
    ap.add_argument("--timing-images", type=Path)
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args(argv)
    if not a.data and not a.timing_images:
        ap.error("nothing to do: pass --data (labelled test set) and/or --timing-images")

    registry = model_registry.load(a.registry)
    active = model_registry.entry(registry, registry["active"])
    model_path = a.model or (a.registry.parent / model_registry.file_of(active))
    version = registry["active"] if a.model is None else a.model.stem
    report = {
        "report_type": "TEST_SET_EVALUATION" if a.data else "TIMING_ONLY",
        "generated_at": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "model_version": version,
        "model_sha256": model_registry.sha256_of(model_path),
        "checkpoint_metrics_reference": {
            "source": "training-run validation split recorded in the checkpoint - NOT a test-set result",
            "metrics": (active.get("manifest") or {}).get("metrics") if a.model is None else None,
        },
    }
    if a.data:
        report.update(evaluate_dataset(model_path, a.data, a.split, a.imgsz, a.device))
        report["conditions"] = {}
        for spec in a.condition:
            name, _, yaml_path = spec.partition("=")
            if not name or not yaml_path:
                ap.error(f"--condition must be NAME=YAML, got {spec!r}")
            report["conditions"][name] = evaluate_dataset(model_path, Path(yaml_path), a.split, a.imgsz, a.device)
    if a.timing_images:
        report["latency"] = measure_latency(model_path, a.timing_images, a.runs, a.imgsz, a.device)
    text = json.dumps(report, indent=2)
    if a.out:
        a.out.write_text(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
