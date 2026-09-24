"""
Pure-function tests for app/services/budget_service.py. Same
no-pydantic-import constraint/approach as test_priority_service.py - see
that file's module docstring.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services.budget_service import estimate_budget  # noqa: E402


def test_medium_severity_pothole_matches_base_table_exactly():
    result = estimate_budget(category="POTHOLE", severity="MEDIUM", guardrail_min=500.0, guardrail_max=500000.0)
    assert result["estimated_cost_min"] == 2000.0
    assert result["estimated_cost_max"] == 15000.0
    assert result["guardrail_applied"] is False
    assert result["confidence"] == "PRELIMINARY"
    assert result["estimated_resolution_days"] == 8


def test_severity_multiplier_scales_cost_up_for_critical():
    medium = estimate_budget(category="WATER_LEAKAGE", severity="MEDIUM", guardrail_min=500.0, guardrail_max=500000.0)
    critical = estimate_budget(category="WATER_LEAKAGE", severity="CRITICAL", guardrail_min=500.0, guardrail_max=500000.0)
    assert critical["estimated_cost_min"] > medium["estimated_cost_min"]
    assert critical["estimated_cost_max"] > medium["estimated_cost_max"]
    assert critical["estimated_resolution_days"] < medium["estimated_resolution_days"]


def test_severity_multiplier_scales_cost_down_for_low():
    medium = estimate_budget(category="ILLEGAL_CONSTRUCTION", severity="MEDIUM", guardrail_min=500.0, guardrail_max=500000.0)
    low = estimate_budget(category="ILLEGAL_CONSTRUCTION", severity="LOW", guardrail_min=500.0, guardrail_max=500000.0)
    assert low["estimated_cost_min"] < medium["estimated_cost_min"]
    assert low["estimated_cost_max"] < medium["estimated_cost_max"]


def test_guardrail_clamps_outlier_high_estimate():
    # ILLEGAL_CONSTRUCTION CRITICAL: raw max = 100000 * 2.2 = 220000, clamp guardrail to 50000.
    result = estimate_budget(category="ILLEGAL_CONSTRUCTION", severity="CRITICAL", guardrail_min=500.0, guardrail_max=50000.0)
    assert result["estimated_cost_max"] == 50000.0
    assert result["guardrail_applied"] is True
    assert result["raw_cost_max_before_guardrail"] == 220000.0


def test_guardrail_clamps_outlier_low_estimate():
    # GARBAGE_OVERFLOW LOW: raw min = 1000 * 0.6 = 600, clamp guardrail floor to 2000.
    result = estimate_budget(category="GARBAGE_OVERFLOW", severity="LOW", guardrail_min=2000.0, guardrail_max=500000.0)
    assert result["estimated_cost_min"] == 2000.0
    assert result["guardrail_applied"] is True


def test_unknown_category_falls_back_to_general_table():
    result = estimate_budget(category="SOME_FUTURE_CATEGORY", severity="MEDIUM", guardrail_min=500.0, guardrail_max=500000.0)
    general = estimate_budget(category="GENERAL", severity="MEDIUM", guardrail_min=500.0, guardrail_max=500000.0)
    assert result["estimated_cost_min"] == general["estimated_cost_min"]
    assert result["estimated_cost_max"] == general["estimated_cost_max"]


def test_min_never_exceeds_max_even_under_misconfigured_guardrail():
    # Guardrail max below guardrail min (misconfigured .env) must not violate chk_budget_cost_range.
    result = estimate_budget(category="POTHOLE", severity="MEDIUM", guardrail_min=10000.0, guardrail_max=5000.0)
    assert result["estimated_cost_min"] <= result["estimated_cost_max"]
