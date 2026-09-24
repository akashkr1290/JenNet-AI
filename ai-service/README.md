# JanNet AI - AI Service (`ai-service/`)

Phase 7 deliverable. Python FastAPI microservice implementing the **AI
Analysis Module** (SRS 15.4). Locked-stack component per
`ARCHITECTURE.md` / `PROJECT_PROGRESS.md`.

## What this is (and isn't)

This service implements `POST /api/v1/ai/classify` (SRS 20.3): image
pre-processing (OpenCV) -> YOLOv11 classification -> Gemini contextual
cross-validation -> OCR extraction -> confidence scoring -> a routing
decision (auto-approve vs. Verification Team).

It is **not** wired up to the Spring Boot backend yet - that's Phase 8
(`PROJECT_INTEGRATION.md` Section 1). It does **not** implement duplicate
detection (Phase 9) or severity/priority/budget prediction (Phase 10),
even though those endpoints appear in the same SRS 20.3 table - see
`ARCHITECTURE.md` Section 8's Phase -> Component map.

It ships with **no trained YOLOv11 model** (`ARCHITECTURE.md` Section 5).
Every `/classify` call runs honestly and reports `model_available: false`
until a real model is placed - see `models/README.md`.

## Running locally (NOT VERIFIED in the authoring workspace)

This workspace had no outbound network access and no local Python package
manager reach, so none of this has ever actually been `pip install`-ed,
started, or hit with a real request - same constraint, same discipline, as
every other phase's Maven/Flutter code (see `PROJECT_PROGRESS.md` TESTS
section for what verification *was* possible: syntax/import review, not a
real run).

```bash
cd ai-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# edit .env: set AI_SERVICE_API_KEY at minimum
uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

Then, e.g.:

```bash
curl -X POST http://localhost:8001/api/v1/ai/classify \
  -H "X-Internal-Api-Key: <value from .env>" \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "<base64 JPEG/PNG/WEBP bytes>"}'
```

(`image_base64` is a Phase-7-only testing convenience since no real,
backend-issued `image_url` exists yet - see
`app/core/image_fetch.py`.)

## Project layout

```
app/
  main.py               FastAPI app, startup checks
  config.py              env-var-driven Settings (see .env.example)
  api/
    deps.py               internal API-key auth dependency
    routes/
      classify.py          POST /api/v1/ai/classify
      health.py             GET /health (addition, not SRS-named)
  schemas/
    classify.py            ClassifyRequest/ClassifyResponse
    common.py               ErrorResponse (SRS 20.6 envelope)
  services/
    preprocessing.py        OpenCV quality gate + normalization (SRS 21.3)
    yolo_service.py          YOLOv11 model-loading + inference wrapper (SRS 21.1)
    gemini_service.py        Gemini cross-validation + fallback (SRS 21.2)
    ocr_service.py            OCR extraction + fallback (SRS 21.4)
    pipeline.py                Orchestration + confidence scoring/routing (SRS 15.4)
  core/
    exceptions.py             Custom errors -> SRS 20.6 error envelope
    image_fetch.py             image_url / image_base64 acquisition
    logging_config.py           Audit logging (SRS 15.4 Business Rules)
models/
  README.md                   Weights placement/versioning convention
  (no .pt files committed - see .gitignore)
tests/
  test_preprocessing.py, test_pipeline_routing.py
requirements.txt
.env.example
```

## Testing

`tests/` contains `pytest` unit tests for the parts of the pipeline that
don't require network access or a real trained model (image quality
gating, confidence-scoring/routing logic against synthetic detections).
They have never been executed in the authoring workspace (no `pytest`
install available) - see `PROJECT_PROGRESS.md` for the manual
verification that was performed instead (syntax compile via
`python -m py_compile`, import-path review).
