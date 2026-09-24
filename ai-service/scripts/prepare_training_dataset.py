#!/usr/bin/env python3
"""Verified-feedback -> YOLO dataset - remaining-gaps item 11 (pipeline stage 1).

Input is the backend's AI-feedback export (GET /api/v1/admin/ai-feedback/export,
columns complaint_id, ai_predicted_class, ai_confidence, model_version,
human_final_category, decision_status, duplicate_flagged), the complaint
photos (<complaint_id>.<ext>), and YOLO box annotations (<complaint_id>.txt).

The export carries an IMAGE-level human verdict, not bounding boxes, so boxes
must come from human annotation: rows without an annotation file are written
to needs_annotation.csv instead of being given invented boxes. Rows the
human did not confirm as a detectable civic issue (REJECTED, DUPLICATE, or a
category that is not a model class, e.g. GENERAL) are excluded. Splits are
deterministic (hash of complaint id + seed). Refuses to emit a dataset below
--min-images usable images.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_registry  # noqa: E402

EXCLUDED_DECISIONS = {"REJECTED", "DUPLICATE"}
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".webp")


def split_for(complaint_id: str, seed: int, val: float, test: float) -> str:
    bucket = int(hashlib.sha1(f"{seed}:{complaint_id}".encode()).hexdigest(), 16) % 10_000 / 10_000
    return "test" if bucket < test else "val" if bucket < test + val else "train"


def prepare(feedback_csv: Path, images_dir: Path, labels_dir: Path, class_names: list[str], out_dir: Path,
            seed: int = 42, val: float = 0.1, test: float = 0.1, min_images: int = 50) -> dict:
    class_index = {name: i for i, name in enumerate(class_names)}
    usable, needs_annotation, excluded = [], [], []
    with open(feedback_csv, newline="") as fh:
        for row in csv.DictReader(fh):
            cid = row["complaint_id"].strip()
            category = (row.get("human_final_category") or "").strip().lower()
            if row.get("decision_status", "").strip() in EXCLUDED_DECISIONS or category not in class_index:
                excluded.append(cid)
                continue
            image = next((images_dir / f"{cid}{s}" for s in IMAGE_SUFFIXES if (images_dir / f"{cid}{s}").is_file()), None)
            label = labels_dir / f"{cid}.txt"
            if image is None:
                excluded.append(cid)
                continue
            if not label.is_file():
                needs_annotation.append({"complaint_id": cid, "human_final_category": category,
                                         "ai_predicted_class": row.get("ai_predicted_class", "")})
                continue
            classes_in_label = set()
            valid = True
            for line in label.read_text().splitlines():
                parts = line.split()
                if not parts:
                    continue
                if len(parts) != 5 or not parts[0].isdigit() or int(parts[0]) >= len(class_names):
                    valid = False
                    break
                if not all(0.0 <= float(x) <= 1.0 for x in parts[1:]):
                    valid = False
                    break
                classes_in_label.add(int(parts[0]))
            if not valid or class_index[category] not in classes_in_label:
                # the annotation must contain a box for the human-confirmed category
                needs_annotation.append({"complaint_id": cid, "human_final_category": category,
                                         "ai_predicted_class": row.get("ai_predicted_class", "")})
                continue
            usable.append((cid, image, label))

    summary = {"usable": len(usable), "needs_annotation": len(needs_annotation), "excluded": len(excluded),
               "splits": {"train": 0, "val": 0, "test": 0}}
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / "needs_annotation.csv", "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=["complaint_id", "human_final_category", "ai_predicted_class"])
        writer.writeheader()
        writer.writerows(needs_annotation)
    if len(usable) < min_images:
        summary["written"] = False
        summary["reason"] = f"only {len(usable)} annotated images (< --min-images {min_images}); no dataset written"
        (out_dir / "summary.json").write_text(json.dumps(summary, indent=2))
        return summary
    for cid, image, label in usable:
        split = split_for(cid, seed, val, test)
        summary["splits"][split] += 1
        (out_dir / "images" / split).mkdir(parents=True, exist_ok=True)
        (out_dir / "labels" / split).mkdir(parents=True, exist_ok=True)
        shutil.copy2(image, out_dir / "images" / split / image.name)
        shutil.copy2(label, out_dir / "labels" / split / label.name)
    names = "\n".join(f"  {i}: {n}" for i, n in enumerate(class_names))
    (out_dir / "dataset.yaml").write_text(
        f"path: {out_dir.resolve()}\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n{names}\n")
    summary["written"] = True
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    return summary


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--feedback-csv", type=Path, required=True)
    ap.add_argument("--images-dir", type=Path, required=True)
    ap.add_argument("--labels-dir", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--registry", type=Path, default=model_registry.DEFAULT_REGISTRY)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--min-images", type=int, default=50)
    a = ap.parse_args(argv)
    registry = model_registry.load(a.registry)
    names_map = model_registry.entry(registry, registry["active"])["manifest"]["class_names"]
    class_names = [names_map[k] for k in sorted(names_map, key=int)]
    summary = prepare(a.feedback_csv, a.images_dir, a.labels_dir, class_names, a.out_dir,
                      seed=a.seed, min_images=a.min_images)
    print(json.dumps(summary, indent=2))
    return 0 if summary.get("written") else 3


if __name__ == "__main__":
    sys.exit(main())
