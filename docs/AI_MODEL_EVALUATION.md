# AI Model Evaluation — YOLOv11 Civic Issue Detection

Gap-backlog Patch 2 (Sep 2026 audit) deliverable. Every number in this
document was either read directly from the shipped model checkpoint or
produced by actually running that checkpoint through the real inference
pipeline in this sandbox (see "How this was verified" below) - nothing
here is estimated or assumed.

## Active model: `yolov11-civic-v1.1` (added 2026-09-28)

`yolov11-civic-v1.0` (documented in the rest of this file) detected
nothing on real citizen photos: its training set (`jannet-civic-issues-2`)
was mostly dashcam-video pothole frames, indoor leaks, crowds labelled as
illegal construction, closed manhole covers and working street lights.
`v1.1` was retrained from scratch on a new, hand-checked dataset.

| Field | Value |
|---|---|
| Model version | `yolov11-civic-v1.1` |
| Base architecture | `yolo11s.pt` |
| Dataset | Roboflow `akash-kumar-saurav/jannet-civic-v2-qhmot`, version 2 (2,458 images, fit-in 640×640, no offline augmentation) |
| Classes | `garbage_overflow`, `open_manhole`, `pothole` (3) |
| Training | free Google Colab T4, 80 epochs, patience 20, batch 16, Ultralytics 8.4.164 |
| SHA256 | `0a45360fbdf3860a4be39914346b8e712b622578875769245c06c214f658ea9d` |

**Why only 3 classes.** No trustworthy public data was found for
`broken_street_light` (public sets show working or merely unlit lamps),
`water_leakage` (video frames of a few scenes, indoor spills) or
`illegal_construction`. Training on misleading pictures is what broke
v1.0, so these three are left to Gemini: when YOLO finds nothing at or
above `MIN_DETECTION_THRESHOLD`, `pipeline.py` uses Gemini's category
(`GEMINI_CATEGORY_NO_DETECTION`). No code change was needed - the service
reads class names from the weights file.

| mAP50 | garbage_overflow | open_manhole | pothole | all |
|---|---|---|---|---|
| validation split (298 images) | 0.407 | 0.971 | 0.766 | 0.715 |
| test split (144 images, unseen) | 0.284 | 0.959 | 0.796 | 0.679 |

Real photos outside the dataset (top box, conf ≥ 0.25; v1.0 found nothing on any of them):

| Photo | v1.1 |
|---|---|
| pothole, dry road | pothole 75% |
| broken manhole | open_manhole 40% (below 50% threshold → Gemini) |
| potholes in rain | open_manhole 83%, pothole 80% (confusion) |
| garbage by a stream | garbage_overflow 56% |
| garbage with goats | garbage_overflow 47% (below threshold → Gemini) |
| pothole (AI-generated) | pothole 74% |

**Known weaknesses:** garbage on street-level photos is weak (only 120
training images are real roadside dumps; the rest are close-up items);
a water-filled pothole can be mistaken for an open manhole. Next
improvement: more real street photos of garbage dumps.

**Dataset credits:** pothole images - Brad Dwyer (Roboflow Universe);
garbage - AGX (CC BY 4.0) and "Roadside Garbage Detection" by Akshitas
Workspace (Roboflow Universe); open manholes - "manhole" by air
(CC BY 4.0), classes uncovered/broke/lose merged into `open_manhole`.

**How the category is decided (shown to staff and citizens since v0.1.6):**
1. *YOLO detection* - a box counts only at or above `MIN_DETECTION_THRESHOLD`
   (50%). The app shows the class, score and threshold; when nothing passes it
   shows YOLO's best guess and that it was below the threshold
   (`raw_model_output.yolo.best_below_threshold`, never used as a detection).
2. *Gemini verification* - confirms or revises YOLO's class, or names the
   issue when YOLO found nothing.
3. *Result* - auto-approved only at `AUTO_APPROVE_CONFIDENCE_THRESHOLD` (85%);
   otherwise an officer confirms. When neither YOLO nor Gemini recognises the
   issue it goes to manual review. The stage that decided is recorded in
   `raw_model_output.decision.outcome` (`YOLO_CONFIRMED_BY_GEMINI`, `YOLO_ONLY`,
   `GEMINI_VERIFIED`, `GEMINI_REVISED`, `YOLO_GEMINI_DISAGREED`, `OCR_HINT`,
   `MANUAL_REVIEW`, `MODEL_UNAVAILABLE`) and returned by the backend as
   `aiClassification.decision`.

**Rollback:** set `YOLO_MODEL_PATH=models/yolov11-civic-v1.0.pt` and
`YOLO_MODEL_VERSION=yolov11-civic-v1.0` (and `"active"` in
`models/registry.json`), then restart - v1.0 still ships in the image.

## Previous model: `yolov11-civic-v1.0`

### Model identity

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

## Evaluation and model-lifecycle procedure (remaining-gaps items 1, 11, 13)

All commands run from `ai-service/`. Nothing below has produced a test-set metric yet: this
project has **no labelled test set**. Checkpoint metrics above remain training-run validation
figures only.

1. **Evaluate** (reproducible; report records model and dataset sha256):
   `python scripts/evaluate_model.py --data <test.yaml> [--condition low_light=<yaml> ...] [--timing-images <dir>] --out eval-active.json`
2. **Prepare training data** from the backend export (`GET /api/v1/admin/ai-feedback/export`), photos
   and human YOLO annotations: `python scripts/prepare_training_dataset.py --feedback-csv fb.csv --images-dir img --labels-dir lbl --out-dir ds`
   (unannotated rows go to `ds/needs_annotation.csv`; boxes are never invented).
3. **Train a candidate** (never activated): `python scripts/train_candidate.py --data ds/dataset.yaml --version yolov11-civic-v1.1`
4. **Evaluate the candidate on the same test set**, then gate:
   `python scripts/evaluation_gate.py --candidate eval-candidate.json --active eval-active.json --out gate.json`
5. **Human approval** (refused unless the gate passed): `python scripts/model_registry.py approve --version yolov11-civic-v1.1 --approved-by "<name>" --gate-report gate.json`
6. **Promote** / **roll back** (sha256-verified, audited in `registry.json` `history`):
   `python scripts/model_registry.py promote --version yolov11-civic-v1.1 --actor "<name>"`,
   `python scripts/model_registry.py rollback --actor "<name>" [--to <known-good version>]`,
   then restart ai-service with `USE_MODEL_REGISTRY=true` (or apply the printed env values).

Limitations: rollback is deliberately manual (no reliable automatic quality signal exists);
latency figures are hardware-dependent; difficult-condition results require separately
labelled subsets.
