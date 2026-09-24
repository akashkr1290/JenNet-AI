# AI Model Evaluation — YOLOv11 Civic Issue Detection

Gap-backlog Patch 2 (Sep 2026 audit) deliverable. Every number in this
document was either read directly from the shipped model checkpoint or
produced by actually running that checkpoint through the real inference
pipeline in this sandbox (see "How this was verified" below) - nothing
here is estimated or assumed.

## Model identity

| Field | Value |
|---|---|
| Model version | `yolov11-civic-v1.0` |
| Base architecture | `yolo11s.pt` (Ultralytics YOLOv11-small) |
| Dataset | `jannet-civic-issues-2` |
| Date trained | 2026-09-18 |
| Configured epochs | 100 |
| Image size | 640×640 |
| Ultralytics version | 8.3.40 |
| SHA256 | `f052caf8e9fac3540d4f79bc7772957268344e65f320d135b6cfda430b9f6fa8` |

See `models/registry.json` for the machine-readable version of this
table (Gap-backlog Patch 3), and `scripts/generate_model_manifest.py`
for how to regenerate it from any weights file.

## Validation metrics (from the training run's own held-out split)

These are the metrics Ultralytics computed and stored inside the
checkpoint at the end of training, against that training run's own
validation split - not independently re-measured in this sandbox (no
Maven-Central-style network restriction here; the real constraint is
that the training run's original validation images aren't present in
this repo, only the trained weights are).

| Metric | Value |
|---|---|
| Precision | 0.704 |
| Recall | 0.653 |
| mAP50 | 0.680 |
| mAP50-95 | 0.406 |
| Box loss (val) | 1.724 |
| Class loss (val) | 1.323 |
| DFL loss (val) | 1.581 |

**Reading these numbers honestly**: mAP50 of 0.68 means the model finds
and correctly classifies civic issues reasonably well under a lenient
overlap threshold; the considerably lower mAP50-95 (0.406) means
bounding-box localization precision drops off at stricter overlap
thresholds - boxes are often "in the right area" more than "tightly
fitted." Recall of 0.653 means roughly a third of real issues in the
validation set were missed entirely. This is a usable first model for a
pilot with human verification in the loop (which the system already
routes low-confidence predictions to - see `routing_reason:
"BELOW_AUTO_APPROVE_THRESHOLD"`), not a production-grade detector that
should be trusted to auto-approve confidently on its own.

## Per-class performance

**Not available.** The checkpoint's stored `train_metrics` are
aggregate-only (all classes combined) - Ultralytics does store
per-class breakdowns in its training-run results CSV/plots, but those
artifacts were not included alongside the weights file itself, only the
final aggregate numbers above. Per-class precision/recall/mAP, false
positive analysis, and false negative sample review all require either
the original training run's full output directory or a fresh evaluation
run against a held-out labeled dataset - neither is available in this
sandbox. **This is the single most valuable next step for a real
evaluation pass**, ahead of anything else in this document.

## Difficult-scenario testing

**Not yet done**, and flagged here rather than silently skipped, per
this project's own standing convention (decisions-and-principles.md:
"Honest documentation of limitations"). None of the following have been
tested against this model in this sandbox, because no curated,
labeled test images for these conditions exist in this repo:

- Low-light / night images
- Rain / wet-lens conditions
- Varied camera angles and distances
- Multiple objects in one frame
- Partially visible / occluded objects
- Crowded backgrounds
- Coverage across different Indian road/street environments

Closing this gap needs a small curated test set (even 10-20 real photos
per condition would be a meaningful start) run through the real pipeline
(`app/services/pipeline.classify`) with results logged per image - the
harness for this (real YOLO inference, real preprocessing/quality-gate
pipeline) already exists and works (see below); only the labeled test
images themselves are missing.

## Inference time

**Partially measured.** A single synthetic 640×640 image through the
real model (CPU inference, no GPU, in this sandbox) completed in
well under a second end-to-end (preprocessing + YOLO + routing logic;
Gemini/OCR add their own separate latency - see Gap-backlog Patch 32's
open item for structured, persisted per-stage timing). This is a single
anecdotal data point, not a P50/P95/P99 distribution under realistic
concurrent load - that requires the load-testing pass Gap-backlog Patch
54 covers, not this document.

## How this was verified

Run in this sandbox, this session, against the real shipped checkpoint
(not mocked):

1. `models/yolov11-civic-v1.0.pt` loads successfully via
   `ultralytics.YOLO(...)` - confirmed the class map matches the six
   expected civic-issue categories.
2. The real checkpoint's `train_metrics` dict was read directly (the
   table above) via `torch.load(..., weights_only=False)`.
3. A full, real, end-to-end run of `app/services/pipeline.classify()` -
   preprocessing → YOLO inference → confidence scoring → routing -
   against a synthetic test image completed successfully and returned a
   real detection with a real confidence score and correct routing
   decision.
4. `tests/test_yolo_service.py`'s `TestYoloServiceRealWeightsFile` class
   (added this session) exercises the real-model-loaded branch on every
   test run going forward, not just this one-off manual check.

## Known limitations (carried into `models/registry.json`)

- No per-class breakdown (see above) - highest-priority next step.
- No difficult-scenario testing (see above).
- Inference-time figures are anecdotal, not a real load-tested
  distribution.
- `epochs_configured` (100) is training-run *configuration*, not
  independent confirmation that all 100 epochs completed without early
  stopping or interruption - not claimed as a confirmed fact here.
