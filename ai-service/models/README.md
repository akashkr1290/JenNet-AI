# models/

Placement and versioning convention for trained model weights used by the
AI Service (Phase 7 scaffolding; ARCHITECTURE.md Section 5), extended by
Gap-backlog Patches 1-3 (Sep 2026 audit) once a real trained model
actually shipped.

## A real trained model ships with this repository

`yolov11-civic-v1.0.pt` is a real Ultralytics-format YOLOv11 checkpoint,
trained on the `jannet-civic-issues-2` dataset (100 configured epochs,
base `yolo11s.pt`). `app/services/yolo_service.py` looks for a weights
file at the path configured by `YOLO_MODEL_PATH` (default:
`models/yolov11-civic-v1.0.pt`, relative to `ai-service/`) - Gap-backlog
Patch 1 fixed a prior filename mismatch (`app/config.py` used to default
to `models/yolov11_civic.pt`, which never matched the shipped file, so
`model_available` was silently `false` out of the box despite a real
model being present; see decisions-and-principles.md for the full
record). With the default, unmodified config, real inference now runs.

If `YOLO_MODEL_PATH` ever points at a path with no file (misconfiguration,
a rollback to a version whose file wasn't deployed, etc.), the service
still fails safe exactly as originally designed: `/api/v1/ai/classify`
returns `model_available: false`, `confidence: 0`,
`requires_manual_review: true`, `routing_reason: "MODEL_UNAVAILABLE"` -
it never invents a detection. `tests/test_yolo_service.py`'s
`TestYoloServiceNoWeightsFile` class exercises this branch explicitly.

## Model registry (Gap-backlog Patch 2)

`models/registry.json` tracks every version's metadata: dataset,
training config, epochs, real validation metrics (precision/recall/
mAP50/mAP50-95, read directly out of the checkpoint - never hand-typed),
class mapping, and a SHA256 hash of the weights file. Generate a new
version's entry with:

```
python scripts/generate_model_manifest.py models/<new-version>.pt
```

This prints a JSON object with every field read from the checkpoint
itself or computed from the file's real bytes - review and append it to
`registry.json`'s `versions` array by hand (deliberately not
auto-appended: a new model version is a decision a human should commit
to, not something a script silently records).

**Rollback**: point `YOLO_MODEL_PATH`/`YOLO_MODEL_VERSION` (`.env` or the
real deployment's environment) at an older version's `file_name`/
`model_version`, update `registry.json`'s `"active"` field to match, and
restart the service - `YoloService._ensure_loaded()` loads lazily on
first use, so no code change is required for a rollback, only
config + which file is actually present on disk (or in the future object
store - see "Future: real model registry" below).

## Adding a new model version

1. Train or obtain an Ultralytics-format YOLOv11 `.pt` weights file whose
   class names match `app/services/yolo_service.EXPECTED_CLASSES`
   (`pothole`, `garbage_overflow`, `water_leakage`, `broken_street_light`,
   `open_manhole`, `illegal_construction`) - a real trained model's class
   map is read directly from the weights file at load time, not from that
   Python constant (the constant is documentation of the target taxonomy,
   not the runtime source of truth).
2. Place the file at `ai-service/models/<version-name>.pt` - e.g.
   `yolov11-civic-v1.1.pt`. Do **not** commit it: `.gitignore` already
   excludes `ai-service/models/*.pt` (and `.onnx`/`.weights`) - real model
   binaries belong in a model registry / object storage per an
   infrastructure phase's decision, not source control.
3. Run `scripts/generate_model_manifest.py` against it and append the
   result to `registry.json`'s `versions` array (see above).
4. Set `YOLO_MODEL_PATH=models/<version-name>.pt` and
   `YOLO_MODEL_VERSION=<version-name>` in `.env` (or the real deployment's
   environment), and update `registry.json`'s `"active"` field.
   `YOLO_MODEL_VERSION` is a free-text label written verbatim into
   `predictions.model_version` (SRS 19.6) - keep it matching
   `registry.json`'s `model_version` for that entry.
5. Restart the service.

## Future: real model registry / S3

`registry.json` is a real, git-committed record of *metadata*, not the
weights files themselves (which stay out of source control per step 2
above) - it is deliberately not yet backed by S3/a real artifact registry
with automatic version resolution, per Gap-backlog Patch 3's own
"Future structure: Model Registry / S3 → JanNet AI Service → Active
Model" framing. This file's `versions[].manifest` schema is the
integration contract a future phase wiring that up should read from/
write to, so the metadata itself doesn't need to change shape when the
storage backend does.

## Dataset / training / eval

Dataset collection/labeling, training configuration, and the evaluation
harness live outside this repo (the training run that produced
`yolov11-civic-v1.0.pt` was executed elsewhere, per `registry.json`'s
`date_trained`/`dataset` fields) - this directory is the fixed
integration contract a training run's output lands against, not where
training itself happens. See `docs/AI_MODEL_EVALUATION.md` (Gap-backlog
Patch 2's other deliverable) for what has and hasn't been measured beyond
the training run's own held-out validation split.
