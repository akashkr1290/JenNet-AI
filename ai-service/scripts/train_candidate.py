#!/usr/bin/env python3
"""Train a CANDIDATE model - remaining-gaps item 11 (pipeline stage 2).

Fine-tunes from the active model on a dataset built by
prepare_training_dataset.py, copies the best checkpoint to
models/<version>.pt (never overwriting an existing file) and registers it with
status "candidate". It is NEVER made active here. Next steps are printed:

  evaluate_model.py (candidate and active, same test set)
    -> evaluation_gate.py -> model_registry.py approve (named human)
    -> model_registry.py promote (named human) -> restart ai-service

Requires a real annotated dataset and, realistically, a GPU; not executed in
the environment that added it.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_registry  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--version", required=True, help="new, unique model version name, e.g. yolov11-civic-v1.1")
    ap.add_argument("--registry", type=Path, default=model_registry.DEFAULT_REGISTRY)
    ap.add_argument("--epochs", type=int, default=50)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--device", default="0")
    ap.add_argument("--work-dir", type=Path, default=Path("runs/candidates"))
    a = ap.parse_args(argv)

    registry = model_registry.load(a.registry)
    target = a.registry.parent / f"{a.version}.pt"
    if target.exists() or any(v["model_version"] == a.version for v in registry["versions"]):
        print(f"REFUSED: {a.version} already exists - versions are immutable", file=sys.stderr)
        return 2
    if not a.data.is_file():
        print(f"REFUSED: dataset yaml {a.data} not found", file=sys.stderr)
        return 2
    base = a.registry.parent / model_registry.file_of(model_registry.entry(registry, registry["active"]))

    from ultralytics import YOLO
    model = YOLO(str(base))
    result = model.train(data=str(a.data), epochs=a.epochs, imgsz=a.imgsz, seed=a.seed, deterministic=True,
                         device=a.device, project=str(a.work_dir), name=a.version, exist_ok=False)
    best = Path(result.save_dir) / "weights" / "best.pt"
    shutil.copy2(best, target)
    model_registry.register(a.registry, a.version, target.name)
    print(f"registered {a.version} as CANDIDATE (not active). Next: evaluate_model.py -> evaluation_gate.py "
          f"-> model_registry.py approve -> model_registry.py promote")
    return 0


if __name__ == "__main__":
    sys.exit(main())
