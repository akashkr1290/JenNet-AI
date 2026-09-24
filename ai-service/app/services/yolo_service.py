"""
YOLOv11 object/issue detection wrapper (SRS 15.4 Features, 21.1).

Per ARCHITECTURE.md Section 5: "No trained model exists yet. Phase 7 (AI
Service) will build the real model-loading architecture, dataset structure,
training/eval/inference configuration, and versioning/placement
conventions - but will not fabricate a trained model, an accuracy number,
or a live prediction. Anything model-dependent is explicitly marked
unavailable until a real model is trained and placed per that phase's
documented instructions."

This module is therefore real, functional model-loading code that will
correctly load and run inference against a real Ultralytics YOLO ``.pt``
weights file the moment one is placed at settings.yolo_model_path (see
models/README.md for the placement/versioning convention) - but since no
such file ships with this repo, `is_available()` is False in every
environment until someone adds one, and `classify()` always returns the
documented "no model available" result rather than inventing a prediction.
"""
from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import Any

import numpy as np

from app.config import active_model, get_settings

logger = logging.getLogger(__name__)

# Fixed square input resolution for the model. Matches
# services/preprocessing.py's normalization step.
MODEL_INPUT_SIZE = (640, 640)

# The six civic-issue classes (ARCHITECTURE.md Section 5) plus the
# GENERAL fallback used when nothing is detected/loaded. Index position
# here is arbitrary Phase-7 scaffolding, not a trained model's real
# class-index mapping - a real weights file carries its own class map,
# which _load_model reads instead of this constant once a model loads.
EXPECTED_CLASSES: tuple[str, ...] = (
    "pothole",
    "garbage_overflow",
    "water_leakage",
    "broken_street_light",
    "open_manhole",
    "illegal_construction",
)


class DetectionResult:
    __slots__ = ("class_name", "confidence", "bounding_box")

    def __init__(self, class_name: str, confidence: float, bounding_box: dict[str, float]) -> None:
        self.class_name = class_name
        self.confidence = confidence
        self.bounding_box = bounding_box

    def to_dict(self) -> dict[str, Any]:
        return {
            "class_name": self.class_name,
            "confidence": self.confidence,
            "bounding_box": self.bounding_box,
        }


class YoloService:
    """
    Thin, lazily-initialized wrapper around ultralytics.YOLO.

    Loading is attempted once (lock-guarded) on first use rather than at
    import time, so the rest of the service starts up cleanly even when no
    weights file is present - "model unavailable" is a normal, expected
    runtime state this phase, not a startup failure.
    """

    def __init__(self) -> None:
        self._model: Any | None = None
        self._load_attempted = False
        self._load_error: str | None = None
        self._lock = threading.Lock()

    def is_available(self) -> bool:
        self._ensure_loaded()
        return self._model is not None

    def unavailable_reason(self) -> str | None:
        self._ensure_loaded()
        return self._load_error

    def classify(self, normalized_image: np.ndarray) -> list[DetectionResult]:
        """
        Run inference. Returns an empty list if no model is loaded - callers
        MUST check is_available() first if they need to distinguish "model
        unavailable" from "model ran and detected nothing above threshold".
        """
        self._ensure_loaded()
        if self._model is None:
            return []

        settings = get_settings()
        try:
            results = self._model.predict(
                source=normalized_image, verbose=False, conf=0.0
            )
        except Exception as exc:  # pragma: no cover - defensive, real-model-only path
            logger.error("YOLOv11 inference failed: %s", exc)
            return []

        detections: list[DetectionResult] = []
        for result in results:
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue
            names = result.names
            for box in boxes:
                cls_index = int(box.cls[0])
                confidence_pct = float(box.conf[0]) * 100.0
                if confidence_pct < settings.min_detection_threshold:
                    continue
                xyxy = box.xyxy[0].tolist()
                detections.append(
                    DetectionResult(
                        class_name=str(names.get(cls_index, "unknown")),
                        confidence=confidence_pct,
                        bounding_box={
                            "x1": xyxy[0], "y1": xyxy[1], "x2": xyxy[2], "y2": xyxy[3],
                        },
                    )
                )

        detections.sort(key=lambda d: d.confidence, reverse=True)
        return detections

    def _ensure_loaded(self) -> None:
        if self._load_attempted:
            return
        with self._lock:
            if self._load_attempted:
                return
            self._load_attempted = True
            settings = get_settings()
            model_path = Path(active_model(settings)[0])  # remaining-gaps item 13

            if not model_path.exists():
                self._load_error = (
                    f"No YOLOv11 weights file at '{model_path}' - "
                    "see models/README.md for how to add one."
                )
                logger.warning(self._load_error)
                return

            try:
                from ultralytics import YOLO  # imported lazily - heavy, optional at runtime
                self._model = YOLO(str(model_path))
                logger.info("Loaded YOLOv11 model from %s", model_path)
            except Exception as exc:
                self._load_error = f"Failed to load YOLOv11 model from '{model_path}': {exc}"
                logger.error(self._load_error)
                self._model = None


_service_singleton: YoloService | None = None
_singleton_lock = threading.Lock()


def get_yolo_service() -> YoloService:
    global _service_singleton
    if _service_singleton is None:
        with _singleton_lock:
            if _service_singleton is None:
                _service_singleton = YoloService()
    return _service_singleton
