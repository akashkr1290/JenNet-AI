#!/usr/bin/env python3
"""Evaluation gate - remaining-gaps item 11.

Compares a CANDIDATE model's evaluation report with the ACTIVE model's report
(both produced by scripts/evaluate_model.py on the SAME test set) and writes a
gate report consumed by `model_registry.py approve`. The gate never promotes
anything; it only states whether promotion may be considered by a human.

Rules (defaults are conservative and configurable):
  * both reports must come from the same dataset file (same sha256);
  * candidate mAP50 >= active mAP50 - --max-map-drop (default 0.0);
  * no class present in both reports may lose more than --max-class-recall-drop
    (default 0.05) recall.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def gate(candidate: dict, active: dict, max_map_drop: float = 0.0, max_class_recall_drop: float = 0.05) -> dict:
    reasons = []
    if candidate.get("dataset_sha256") != active.get("dataset_sha256"):
        reasons.append("reports were produced on different datasets")
    c_map, a_map = candidate["overall"]["map50"], active["overall"]["map50"]
    if c_map < a_map - max_map_drop:
        reasons.append(f"mAP50 {c_map:.4f} < active {a_map:.4f} - {max_map_drop}")
    for cls, a_stats in active.get("per_class", {}).items():
        c_stats = candidate.get("per_class", {}).get(cls)
        if c_stats is None:
            reasons.append(f"class {cls} missing from candidate evaluation")
        elif c_stats["recall"] < a_stats["recall"] - max_class_recall_drop:
            reasons.append(f"class {cls} recall {c_stats['recall']:.4f} dropped more than {max_class_recall_drop}")
    return {
        "candidate_version": candidate.get("model_version"),
        "active_version": active.get("model_version"),
        "passed": not reasons,
        "reasons": reasons,
        "thresholds": {"max_map_drop": max_map_drop, "max_class_recall_drop": max_class_recall_drop},
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--candidate", type=Path, required=True)
    ap.add_argument("--active", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-map-drop", type=float, default=0.0)
    ap.add_argument("--max-class-recall-drop", type=float, default=0.05)
    a = ap.parse_args(argv)
    report = gate(json.loads(a.candidate.read_text()), json.loads(a.active.read_text()),
                  a.max_map_drop, a.max_class_recall_drop)
    a.out.write_text(json.dumps(report, indent=2) + "\n")
    print("GATE PASSED" if report["passed"] else "GATE FAILED: " + "; ".join(report["reasons"]))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
