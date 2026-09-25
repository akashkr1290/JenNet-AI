"""
JanNet AI - AI Service FastAPI application (Phase 7: classify; Phase 9:
duplicate-check; Phase 10: priority-predict, budget-predict).

Run locally: uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
(requires `pip install -r requirements.txt` first - see README.md; NOT
VERIFIED in this workspace, no network/package-manager reach - see
PROJECT_PROGRESS.md TESTS section.)
"""
from __future__ import annotations

import logging

from fastapi import FastAPI

from app.api.routes.budget_predict import router as budget_predict_router
from app.api.routes.classify import router as classify_router
from app.api.routes.duplicate_check import router as duplicate_check_router
from app.api.routes.health import router as health_router
from app.api.routes.monitoring import router as monitoring_router
from app.api.routes.priority_predict import router as priority_predict_router
from app.api.routes.quality import router as quality_router
from app.config import get_settings
from app.core.exceptions import register_exception_handlers
from app.core.logging_config import configure_logging

configure_logging()

app = FastAPI(
    title="JanNet AI - AI Service",
    description=(
        "AI Analysis Module (SRS 15.4) - internal microservice consumed only "
        "by the Spring Boot backend (Phase 8 wires the actual call). "
        "Locked stack component per ARCHITECTURE.md."
    ),
    version="0.1.0-phase10",
)

register_exception_handlers(app)
app.include_router(health_router)
app.include_router(classify_router)
app.include_router(duplicate_check_router)
app.include_router(priority_predict_router)
app.include_router(budget_predict_router)
app.include_router(monitoring_router)
app.include_router(quality_router)  # audit GAP-032


@app.on_event("startup")
async def on_startup() -> None:
    settings = get_settings()
    if settings.ai_service_api_key == "change-me-in-every-real-environment" and settings.is_local:
        # Audit GAP-035: tolerated only for local development; docker-compose
        # now publishes port 8001 on 127.0.0.1 only.
        logging.getLogger(__name__).warning(
            "AI_SERVICE_API_KEY is the public default value - acceptable only for local "
            "development (AI_SERVICE_ENV=local). Set a real key everywhere else."
        )
    if settings.require_model:
        from app.services.yolo_service import get_yolo_service

        yolo = get_yolo_service()
        if not yolo.is_available():
            raise RuntimeError(
                "REQUIRE_MODEL=true but the YOLO model is not available: "
                f"{yolo.unavailable_reason()} (see ai-service/scripts/verify_model.py)"
            )
    if not settings.is_local and settings.ai_service_api_key == "change-me-in-every-real-environment":
        # Fail loudly rather than silently accepting the default key
        # outside local development - mirrors the backend's own
        # env-var-driven secret-handling convention (.env.example note).
        raise RuntimeError(
            "AI_SERVICE_API_KEY must be set to a real value when "
            "AI_SERVICE_ENV != local."
        )
