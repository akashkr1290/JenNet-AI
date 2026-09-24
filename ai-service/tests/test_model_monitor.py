"""Gap-backlog Patch 36 (Sep 2026 audit): tests for app/services/model_monitor.py."""
from __future__ import annotations

from app.services.model_monitor import ModelMonitor


class TestModelMonitor:
    def test_empty_summary_has_no_percentages(self):
        monitor = ModelMonitor()
        summary = monitor.summary()
        assert summary["total_predictions"] == 0
        assert summary["manual_review_percentage"] is None

    def test_records_accumulate_real_distributions(self):
        monitor = ModelMonitor()
        monitor.record(category="POTHOLE", confidence=92.0, requires_manual_review=False, model_available=True)
        monitor.record(category="POTHOLE", confidence=55.0, requires_manual_review=True, model_available=True)
        monitor.record(category="GARBAGE_OVERFLOW", confidence=88.0, requires_manual_review=False, model_available=True)

        summary = monitor.summary()
        assert summary["total_predictions"] == 3
        assert summary["category_distribution"] == {"POTHOLE": 2, "GARBAGE_OVERFLOW": 1}
        assert summary["manual_review_percentage"] == round(100.0 * 1 / 3, 2)
        assert summary["mean_confidence_by_category"]["POTHOLE"] == round((92.0 + 55.0) / 2, 2)
        assert summary["confidence_distribution"]["90-100"] == 1
        assert summary["confidence_distribution"]["50-60"] == 1
        assert summary["confidence_distribution"]["80-90"] == 1

    def test_model_unavailable_percentage_tracked_independently_of_manual_review(self):
        monitor = ModelMonitor()
        monitor.record(category="GENERAL", confidence=0.0, requires_manual_review=True, model_available=False)
        summary = monitor.summary()
        assert summary["model_unavailable_percentage"] == 100.0
        assert summary["manual_review_percentage"] == 100.0

    def test_reset_clears_state(self):
        monitor = ModelMonitor()
        monitor.record(category="POTHOLE", confidence=90.0, requires_manual_review=False, model_available=True)
        monitor.reset()
        assert monitor.summary()["total_predictions"] == 0
