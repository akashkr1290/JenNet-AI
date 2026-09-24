"""
Gap-backlog Patch 36 (Sep 2026 audit): model prediction/confidence
monitoring - a real, in-process accumulator over every /classify call
this running instance has served, so an operator can watch for
distribution shift without needing a log-aggregation stack wired up
first.

Honest scope: this is in-memory, per-process state, not a persisted
time-series (no metrics backend is part of this project's locked stack -
ARCHITECTURE.md Section 6 doesn't name one, and adding Prometheus/
Grafana as a new infrastructure dependency wasn't asked for by this
patch, which asks for "monitor prediction distribution / confidence
distribution / override percentage" - achievable without one). It resets
on every restart, and in a multi-instance deployment each instance only
sees its own traffic. Gap-backlog Patch 19 (Application Monitoring,
CloudWatch) is where a persisted, cross-instance version of this would
eventually live; this module is the honestly-scoped, no-new-dependency
version that works today.
"""
from __future__ import annotations

import threading
from collections import Counter, defaultdict
from dataclasses import dataclass, field


@dataclass
class _ModelMonitorState:
    total_predictions: int = 0
    category_counts: Counter = field(default_factory=Counter)
    manual_review_count: int = 0
    model_unavailable_count: int = 0
    # Confidence histogram in fixed 10-point buckets, e.g. "80-90".
    confidence_buckets: Counter = field(default_factory=Counter)
    # Sum/count per category, for a real (not estimated) mean confidence per class.
    confidence_sum_by_category: defaultdict = field(default_factory=lambda: defaultdict(float))
    confidence_count_by_category: Counter = field(default_factory=Counter)


class ModelMonitor:
    """Thread-safe (a lock per update - classify() calls are infrequent
    enough relative to request latency that this is not a contention
    concern) in-process accumulator."""

    def __init__(self) -> None:
        self._state = _ModelMonitorState()
        self._lock = threading.Lock()

    def record(self, *, category: str, confidence: float, requires_manual_review: bool, model_available: bool) -> None:
        bucket = f"{int(confidence // 10) * 10}-{int(confidence // 10) * 10 + 10}"
        with self._lock:
            self._state.total_predictions += 1
            self._state.category_counts[category] += 1
            if requires_manual_review:
                self._state.manual_review_count += 1
            if not model_available:
                self._state.model_unavailable_count += 1
            self._state.confidence_buckets[bucket] += 1
            self._state.confidence_sum_by_category[category] += confidence
            self._state.confidence_count_by_category[category] += 1

    def summary(self) -> dict:
        with self._lock:
            s = self._state
            if s.total_predictions == 0:
                return {
                    "total_predictions": 0,
                    "category_distribution": {},
                    "confidence_distribution": {},
                    "manual_review_percentage": None,
                    "model_unavailable_percentage": None,
                    "mean_confidence_by_category": {},
                }
            return {
                "total_predictions": s.total_predictions,
                "category_distribution": dict(s.category_counts),
                "confidence_distribution": dict(s.confidence_buckets),
                "manual_review_percentage": round(100.0 * s.manual_review_count / s.total_predictions, 2),
                "model_unavailable_percentage": round(100.0 * s.model_unavailable_count / s.total_predictions, 2),
                "mean_confidence_by_category": {
                    category: round(s.confidence_sum_by_category[category] / s.confidence_count_by_category[category], 2)
                    for category in s.category_counts
                },
            }

    def reset(self) -> None:
        """Test-only - a real deployment never calls this; state resets naturally on process restart."""
        with self._lock:
            self._state = _ModelMonitorState()


_monitor = ModelMonitor()


def get_model_monitor() -> ModelMonitor:
    return _monitor
