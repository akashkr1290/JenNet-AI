"""
Unit tests for app/services/duplicate_service.py's tiering/geo logic
(SRS 15.6 Business Rules, 21.5 Confidence Score/Fallback Logic).

NOT VERIFIED (not executed) - same NOT VERIFIED constraint as every other
test file in this repo (no pip install possible in this workspace, see
tests/test_preprocessing.py's docstring and PROJECT_PROGRESS.md TESTS).
Deliberately exercises only score_candidate/build_response/
haversine_distance_meters/similarity_from_hashes with synthetic hashes and
coordinates - none of these need cv2 image decoding or a real photo, so
this file is exercising real, reviewable logic even though it has never
actually been run.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.config import get_settings
from app.schemas.duplicate_check import DuplicateCandidate, MatchTier
from app.services.duplicate_service import (
    build_response,
    haversine_distance_meters,
    score_candidate,
    similarity_from_hashes,
)


def _candidate(complaint_id=1, lat=12.9716, lon=77.5946, days_ago=5, ref="JN-2026-000001") -> DuplicateCandidate:
    return DuplicateCandidate(
        complaint_id=complaint_id,
        reference_number=ref,
        image_base64="AAAA",  # not decoded by these tests - hash is passed in directly
        latitude=lat,
        longitude=lon,
        created_at=datetime.now(timezone.utc) - timedelta(days=days_ago),
    )


class TestSimilarityFromHashes:
    def test_identical_hashes_are_100_percent_similar(self):
        assert similarity_from_hashes(0b1010, 0b1010) == 100.0

    def test_fully_inverted_64_bit_hashes_are_0_percent_similar(self):
        all_zero = 0
        all_ones = (1 << 64) - 1
        assert similarity_from_hashes(all_zero, all_ones) == 0.0

    def test_one_bit_difference_in_a_64_bit_hash(self):
        a = 0
        b = 1  # single bit flipped
        assert similarity_from_hashes(a, b) == round(100.0 * (1 - 1 / 64), 2)


class TestHaversineDistance:
    def test_same_point_is_zero_distance(self):
        assert haversine_distance_meters(12.9716, 77.5946, 12.9716, 77.5946) == 0.0

    def test_known_short_distance_is_reasonable(self):
        # Two points ~111m apart (0.001 degree latitude at the equator-ish
        # scale) - a loose sanity bound, not an exact-value assertion.
        distance = haversine_distance_meters(12.9716, 77.5946, 12.9726, 77.5946)
        assert 90 < distance < 130


class TestScoreCandidateTiering:
    def setup_method(self):
        self.settings = get_settings()

    def test_high_similarity_near_recent_candidate_is_auto_merge(self):
        match = score_candidate(
            new_hash=0,
            new_lat=12.9716,
            new_lon=77.5946,
            new_now=datetime.now(timezone.utc),
            candidate=_candidate(lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=0,  # identical -> 100% similarity
            settings=self.settings,
        )
        assert match.tier == MatchTier.AUTO_MERGE
        assert match.similarity_score == 100.0
        assert match.within_proximity is True
        assert match.within_time_window is True

    def test_borderline_similarity_is_manual_review(self):
        # 5 differing bits out of 64 -> ~92% similar; pick a hash pair that
        # lands between the 60 and 80 thresholds instead.
        # 15 differing bits -> 100*(1-15/64) ≈ 76.6%, within [60, 80).
        new_hash = 0
        candidate_hash = (1 << 15) - 1  # 15 low bits set
        match = score_candidate(
            new_hash=new_hash,
            new_lat=12.9716,
            new_lon=77.5946,
            new_now=datetime.now(timezone.utc),
            candidate=_candidate(lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=candidate_hash,
            settings=self.settings,
        )
        assert 60.0 <= match.similarity_score < 80.0
        assert match.tier == MatchTier.MANUAL_REVIEW

    def test_low_similarity_is_not_duplicate(self):
        new_hash = 0
        candidate_hash = (1 << 40) - 1  # 40 differing bits -> well under 60%
        match = score_candidate(
            new_hash=new_hash,
            new_lat=12.9716,
            new_lon=77.5946,
            new_now=datetime.now(timezone.utc),
            candidate=_candidate(lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=candidate_hash,
            settings=self.settings,
        )
        assert match.tier == MatchTier.NOT_DUPLICATE

    def test_high_similarity_but_outside_proximity_is_not_duplicate(self):
        match = score_candidate(
            new_hash=0,
            new_lat=12.9716,
            new_lon=77.5946,
            new_now=datetime.now(timezone.utc),
            # ~1.1km away - well outside the 50m default.
            candidate=_candidate(lat=12.9816, lon=77.5946, days_ago=5),
            candidate_hash=0,
            settings=self.settings,
        )
        assert match.within_proximity is False
        assert match.tier == MatchTier.NOT_DUPLICATE

    def test_high_similarity_but_outside_time_window_is_not_duplicate(self):
        match = score_candidate(
            new_hash=0,
            new_lat=12.9716,
            new_lon=77.5946,
            new_now=datetime.now(timezone.utc),
            candidate=_candidate(lat=12.9716, lon=77.5946, days_ago=45),
            candidate_hash=0,
            settings=self.settings,
        )
        assert match.within_time_window is False
        assert match.tier == MatchTier.NOT_DUPLICATE

    def test_missing_gps_falls_back_to_raised_similarity_threshold(self):
        # No GPS on the new submission -> gps_available False -> 90%
        # threshold applies instead of 80%, and proximity is never checked.
        match = score_candidate(
            new_hash=0,
            new_lat=None,
            new_lon=None,
            new_now=datetime.now(timezone.utc),
            candidate=_candidate(lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=(1 << 10) - 1,  # 10 differing bits -> ~84.4% similar - clears 80 but not the raised 90
            settings=self.settings,
        )
        assert match.within_proximity is None
        assert match.threshold_applied == self.settings.duplicate_no_gps_similarity_threshold
        assert match.tier == MatchTier.MANUAL_REVIEW  # 84.4% clears the manual-review floor but not the raised 90% auto-merge bar


class TestBuildResponse:
    def test_no_candidates_returns_no_candidates_tier(self):
        response = build_response(new_lat=12.9716, new_lon=77.5946, matches=[], model_version="dHash-perceptual-v1")
        assert response.match_tier == MatchTier.NO_CANDIDATES
        assert response.is_duplicate is False
        assert response.parent_complaint_id is None
        assert response.similarity_score == 0.0

    def test_best_match_wins_over_worse_tier_even_if_listed_first(self):
        settings = get_settings()
        now = datetime.now(timezone.utc)
        worse = score_candidate(
            new_hash=0, new_lat=12.9716, new_lon=77.5946, new_now=now,
            candidate=_candidate(complaint_id=1, lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=(1 << 40) - 1, settings=settings,
        )
        better = score_candidate(
            new_hash=0, new_lat=12.9716, new_lon=77.5946, new_now=now,
            candidate=_candidate(complaint_id=2, lat=12.9716, lon=77.5946, days_ago=5),
            candidate_hash=0, settings=settings,
        )
        response = build_response(
            new_lat=12.9716, new_lon=77.5946, matches=[worse, better], model_version="dHash-perceptual-v1"
        )
        assert response.parent_complaint_id == 2
        assert response.is_duplicate is True
