"""
Centralized settings for the AI Service (Phase 7).

All configuration is environment-variable driven (pydantic-settings), matching
this project's established convention from the Spring Boot backend
(application.yml env-var overrides) and Flutter (--dart-define). See
.env.example for the full documented list and SRS section references.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- Service identity / internal auth ---
    ai_service_api_key: str = "change-me-in-every-real-environment"
    ai_service_env: str = "local"

    # --- Server ---
    ai_service_host: str = "0.0.0.0"
    ai_service_port: int = 8001

    # --- Confidence / threshold configuration (SRS 15.4, 21.1) ---
    auto_approve_confidence_threshold: float = 85.0
    min_detection_threshold: float = 50.0
    gemini_fallback_confidence_cap: float = 70.0

    # --- Image quality gate (SRS 21.3) ---
    min_image_width_px: int = 320
    min_image_height_px: int = 320
    blur_variance_threshold: float = 80.0
    max_image_bytes: int = 10 * 1024 * 1024

    # --- YOLOv11 (SRS 21.1) ---
    yolo_model_path: str = "models/yolov11-civic-v1.0.pt"
    yolo_model_version: str = "yolov11-civic-v1.0"
    # Remaining-gaps item 13: when true, the model file/version come from the
    # registry's "active" entry (models/registry.json) instead of the two
    # settings above, so a promotion or rollback made with
    # scripts/model_registry.py takes effect on restart without editing env.
    # Default false keeps the existing env-var behaviour unchanged.
    use_model_registry: bool = False
    # Audit GAP-060: production sets REQUIRE_MODEL=true so the service refuses
    # to start without its trained weights instead of silently answering
    # MODEL_UNAVAILABLE for every complaint. Local/dev default stays lenient.
    require_model: bool = False
    model_registry_path: str = "models/registry.json"

    # --- Gemini API (SRS 21.2) ---
    gemini_api_key: str = ""
    gemini_model_name: str = "gemini-1.5-flash"
    gemini_timeout_seconds: float = 8.0
    # Remaining-gaps item 2: bounded retry for TRANSIENT Gemini errors (429/503/
    # deadline), all inside gemini_timeout_seconds. 0 disables retry.
    gemini_max_retries: int = 1

    # --- OCR (SRS 21.4) ---
    ocr_enabled: bool = True
    tesseract_cmd_path: str = ""

    # --- Outbound image fetch ---
    image_fetch_timeout_seconds: float = 10.0
    # Audit GAP-035 (SSRF): image_url fetching is DISABLED unless the URL's
    # host is listed here (comma-separated, exact host match, https only
    # unless image_url_allow_http=true). The backend always sends
    # image_base64, so the default (empty) keeps the SRS 20.3 image_url
    # field in the contract without letting callers make this service fetch
    # arbitrary internal URLs (e.g. cloud metadata endpoints).
    image_url_allowed_hosts: str = ""
    image_url_allow_http: bool = False

    # --- Duplicate Detection (SRS 15.6, 21.5) ---
    # "a candidate is treated as a duplicate when image similarity exceeds
    # the configured threshold (default 80%) AND the candidate is within
    # 50 meters and 30 days of an existing open complaint" (15.6 Business
    # Rules); "60-80% = manual review queue" (21.5 Confidence Score).
    duplicate_auto_merge_similarity_threshold: float = 80.0
    duplicate_manual_review_similarity_threshold: float = 60.0
    # 21.5 Fallback Logic: "if GPS is unavailable, duplicate detection
    # relies on image similarity alone with a raised threshold (90%)".
    duplicate_no_gps_similarity_threshold: float = 90.0
    duplicate_proximity_meters: float = 50.0
    duplicate_time_window_days: int = 30

    # --- Priority Prediction / Severity Scoring (SRS 15.8, 21.6) ---
    # "corroboration count above a configured threshold raises severity by
    # one level" (15.8 Business Rules).
    priority_corroboration_severity_threshold: int = 3
    # SRS 15.8: "Critical is auto-assigned to any complaint involving a
    # safety hazard category (e.g., open manhole, exposed live wire)
    # regardless of other scoring inputs." Only OPEN_MANHOLE exists as a
    # real category in this project's locked IssueCategory set (no
    # "exposed live wire" category was ever defined - see priority_service.py
    # module docstring for the full reasoning); expressed as a
    # comma-separated env-overridable list so Admin-style configuration
    # doesn't require a code change to add a category later.
    priority_safety_hazard_categories: str = "OPEN_MANHOLE"

    # --- Budget Prediction (SRS 15.9, 21.8) ---
    # SRS 15.9 Validation Rules: "predicted values are bounded by
    # configurable minimum/maximum guardrails per category to prevent
    # outlier predictions." No historical department spend data exists yet
    # in this project (Reports module is Phase 16, not built - see
    # budget_service.py module docstring), so every prediction this phase
    # is honestly a cold-start/PRELIMINARY estimate using these citywide
    # average guardrails, never a per-ward figure.
    budget_guardrail_min_inr: float = 500.0
    budget_guardrail_max_inr: float = 500000.0

    @property
    def image_url_allowed_host_set(self) -> set[str]:
        return {h.strip().lower() for h in self.image_url_allowed_hosts.split(",") if h.strip()}

    @property
    def is_local(self) -> bool:
        return self.ai_service_env.lower() == "local"


@lru_cache
def get_settings() -> Settings:
    """Cached settings singleton - avoids re-parsing env on every request."""
    return Settings()


def active_model(settings: "Settings | None" = None) -> tuple[str, str]:
    """(model file path, model version) the service should load.

    Remaining-gaps item 13. With use_model_registry=false (default) this is
    exactly (yolo_model_path, yolo_model_version) - unchanged behaviour. With
    it enabled, the registry's "active" version and its manifest file_name are
    used; any problem reading the registry falls back to the env settings and
    is logged, so a bad registry edit can never stop the service starting.
    """
    import json
    import logging
    from pathlib import Path

    s = settings or get_settings()
    if not s.use_model_registry:
        return s.yolo_model_path, s.yolo_model_version
    try:
        registry_path = Path(s.model_registry_path)
        registry = json.loads(registry_path.read_text())
        active = registry["active"]
        entry = next(v for v in registry["versions"] if v["model_version"] == active)
        file_name = entry.get("manifest", {}).get("file_name") or entry["file_name"]
        return str(registry_path.parent / file_name), active
    except Exception as exc:  # noqa: BLE001 - never block startup on registry problems
        logging.getLogger(__name__).error(
            "use_model_registry is enabled but the registry could not be resolved (%s); "
            "falling back to YOLO_MODEL_PATH/YOLO_MODEL_VERSION", type(exc).__name__)
        return s.yolo_model_path, s.yolo_model_version
