"""
Pure-function tests for app/services/priority_service.py. Deliberately
avoids importing anything from app/schemas (pydantic) or app/api (fastapi)
- neither package is installed in this sandbox (confirmed again this
phase; same constraint as every prior phase's TESTS section) - so these
tests exercise score_complaint/bump_severity/compute_priority_score
directly with plain str/dict/set arguments, the same "real execution of
the pure algorithmic core" approach Phase 9 used for duplicate_service.py's
dHash/haversine functions.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services.priority_service import (  # noqa: E402
    bump_severity,
    compute_priority_score,
    score_complaint,
)

NO_FLAGS = {"near_school": False, "near_hospital": False, "high_traffic_road": False}


def test_bump_severity_ladder():
    assert bump_severity("LOW") == "MEDIUM"
    assert bump_severity("MEDIUM") == "HIGH"
    assert bump_severity("HIGH") == "CRITICAL"
    assert bump_severity("CRITICAL") == "CRITICAL"  # capped, does not overflow


def test_base_severity_table_lookup_no_overrides():
    result = score_complaint(
        category="POTHOLE",
        corroboration_count=1,
        location_flags=NO_FLAGS,
        safety_hazard_categories={"OPEN_MANHOLE"},
        corroboration_severity_threshold=3,
    )
    assert result["severity"] == "MEDIUM"
    assert result["base_severity"] == "MEDIUM"
    assert result["safety_hazard_override_applied"] is False
    assert result["corroboration_bump_applied"] is False


def test_safety_hazard_override_forces_critical_regardless_of_other_inputs():
    result = score_complaint(
        category="OPEN_MANHOLE",
        corroboration_count=1,
        location_flags=NO_FLAGS,
        safety_hazard_categories={"OPEN_MANHOLE"},
        corroboration_severity_threshold=3,
    )
    assert result["severity"] == "CRITICAL"
    assert result["safety_hazard_override_applied"] is True
    # SRS 15.8: "regardless of other scoring inputs" - corroboration bump
    # must not even attempt to fire once already CRITICAL via override.
    assert result["corroboration_bump_applied"] is False


def test_corroboration_count_above_threshold_bumps_one_level():
    below = score_complaint(
        category="GARBAGE_OVERFLOW",
        corroboration_count=3,
        location_flags=NO_FLAGS,
        safety_hazard_categories={"OPEN_MANHOLE"},
        corroboration_severity_threshold=3,
    )
    above = score_complaint(
        category="GARBAGE_OVERFLOW",
        corroboration_count=4,
        location_flags=NO_FLAGS,
        safety_hazard_categories={"OPEN_MANHOLE"},
        corroboration_severity_threshold=3,
    )
    assert below["corroboration_bump_applied"] is False
    assert below["severity"] == "LOW"  # GARBAGE_OVERFLOW base severity, no bump at exactly the threshold
    assert above["corroboration_bump_applied"] is True
    assert above["severity"] == "MEDIUM"  # bumped one level from LOW


def test_location_sensitivity_flag_bumps_one_level():
    flagged = dict(NO_FLAGS)
    flagged["near_school"] = True
    result = score_complaint(
        category="BROKEN_STREET_LIGHT",
        corroboration_count=1,
        location_flags=flagged,
        safety_hazard_categories={"OPEN_MANHOLE"},
        corroboration_severity_threshold=3,
    )
    assert result["base_severity"] == "LOW"
    assert result["severity"] == "MEDIUM"
    assert result["location_flags_applied"] == {"near_school": True}


def test_priority_score_increases_with_corroboration_but_caps():
    one_report = compute_priority_score("MEDIUM", 1)
    five_reports = compute_priority_score("MEDIUM", 5)
    fifty_reports = compute_priority_score("MEDIUM", 50)
    assert one_report == 50.0
    assert five_reports == 58.0  # 50 base + 4 extra reports * 2 = 58
    assert fifty_reports == 60.0  # bonus capped at +10 regardless of report count
    assert fifty_reports <= 100.0


def test_critical_severity_score_never_exceeds_100():
    score = compute_priority_score("CRITICAL", 100)
    assert score == 100.0
