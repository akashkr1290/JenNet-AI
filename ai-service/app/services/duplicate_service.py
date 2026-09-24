"""
Duplicate Detection Module (SRS 15.6, 21.5): compares a new complaint's
image + GPS against a caller-supplied set of existing open complaints and
decides whether it's a duplicate.

MODEL CHOICE - perceptual hashing, not a learned embedding: SRS 15.6
Features literally offers a choice ("image similarity comparison
(perceptual hashing / embedding similarity)"). ARCHITECTURE.md Section 5
(locked since Phase 7) is explicit that no trained model ships with this
repo and nothing model-dependent should be fabricated - the same
constraint that left YOLOv11 classification `model_available: false` in
every environment until real weights are placed. A learned image-embedding
model has exactly that same problem (no weights, no training pipeline in
scope for this project - see ARCHITECTURE.md Section 5's own "will not
fabricate a trained model" instruction). A difference hash (dHash) needs no
weights, no training data, and no GPU - only OpenCV, already a Phase 7
dependency - and is a real, standard, non-fabricated technique for
near-duplicate photo detection. This is the same kind of honest
degrade-gracefully choice Phase 7 made in reverse for YOLO (build the real
architecture, mark capability unavailable rather than fake a result) -
here the reverse is possible: build a real, always-available capability
that doesn't require a model at all. Recorded in
PROJECT_INTEGRATION.md Section 6.

ALGORITHM: 8x8 difference hash (dHash) - resize to 9x8 grayscale, compare
each pixel to its right-hand neighbor, producing a 64-bit fingerprint.
Similarity = 100 * (1 - hamming_distance / 64). dHash is robust to minor
resizing/recompression/lighting changes (exactly the kind of near-duplicate
variation two different citizens' photos of the same pothole would have)
while still being cheap to compute. This is a real, working implementation,
not a placeholder - unlike yolo_service.py, there is no "model unavailable"
state for this capability.
"""
from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any

import cv2
import numpy as np

from app.config import get_settings
from app.schemas.duplicate_check import (
    DuplicateCandidate,
    DuplicateCheckResponse,
    DuplicateMatch,
    MatchTier,
)

_HASH_SIZE = 8  # -> 8x8 comparisons = 64-bit hash
_HASH_BITS = _HASH_SIZE * _HASH_SIZE
_EARTH_RADIUS_METERS = 6_371_000.0


def compute_perceptual_hash(image_bytes: bytes) -> int:
    """
    Difference hash (dHash) of the given image bytes, as a 64-bit int.
    Raises ValueError if the bytes can't be decoded as an image - callers
    should treat that as "skip this image", not a hard failure. See
    routes/duplicate_check.py, which catches this per-candidate so one bad
    candidate image can't fail the whole request.
    """
    array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_GRAYSCALE)
    if image is None:
        raise ValueError("Image bytes could not be decoded for perceptual hashing.")

    resized = cv2.resize(image, (_HASH_SIZE + 1, _HASH_SIZE), interpolation=cv2.INTER_AREA)
    diff = resized[:, 1:] > resized[:, :-1]

    hash_value = 0
    for bit in diff.flatten():
        hash_value = (hash_value << 1) | int(bit)
    return hash_value


def _hamming_distance(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def similarity_from_hashes(hash_a: int, hash_b: int) -> float:
    """0-100 similarity score; 100 means identical dHash fingerprints."""
    distance = _hamming_distance(hash_a, hash_b)
    return round(100.0 * (1.0 - (distance / _HASH_BITS)), 2)


def haversine_distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return _EARTH_RADIUS_METERS * 2 * math.asin(math.sqrt(a))


def score_candidate(
    *,
    new_hash: int,
    new_lat: float | None,
    new_lon: float | None,
    new_now: datetime,
    candidate: DuplicateCandidate,
    candidate_hash: int,
    settings,
) -> DuplicateMatch:
    similarity = similarity_from_hashes(new_hash, candidate_hash)

    gps_available = new_lat is not None and new_lon is not None \
        and candidate.latitude is not None and candidate.longitude is not None
    distance_meters = (
        haversine_distance_meters(new_lat, new_lon, candidate.latitude, candidate.longitude)
        if gps_available else None
    )
    within_proximity = (distance_meters <= settings.duplicate_proximity_meters) if gps_available else None

    candidate_created_at = candidate.created_at
    if candidate_created_at.tzinfo is None:
        candidate_created_at = candidate_created_at.replace(tzinfo=timezone.utc)
    age_days = (new_now - candidate_created_at).total_seconds() / 86400.0
    within_time_window = 0 <= age_days <= settings.duplicate_time_window_days

    auto_merge_threshold = (
        settings.duplicate_auto_merge_similarity_threshold if gps_available
        else settings.duplicate_no_gps_similarity_threshold
    )

    if gps_available:
        # SRS 15.6 Business Rules: similarity AND proximity AND time window.
        if similarity >= auto_merge_threshold and within_proximity and within_time_window:
            tier = MatchTier.AUTO_MERGE
        elif similarity >= settings.duplicate_manual_review_similarity_threshold and within_proximity and within_time_window:
            tier = MatchTier.MANUAL_REVIEW
        else:
            tier = MatchTier.NOT_DUPLICATE
    else:
        # 21.5 Fallback Logic: "relies on image similarity alone with a
        # raised threshold (90%)" - proximity is not evaluated at all, but
        # the time window still applies (an old photo of a now-fixed issue
        # shouldn't merge just because it looks similar).
        if similarity >= auto_merge_threshold and within_time_window:
            tier = MatchTier.AUTO_MERGE
        elif similarity >= settings.duplicate_manual_review_similarity_threshold and within_time_window:
            tier = MatchTier.MANUAL_REVIEW
        else:
            tier = MatchTier.NOT_DUPLICATE

    return DuplicateMatch(
        complaint_id=candidate.complaint_id,
        reference_number=candidate.reference_number,
        similarity_score=similarity,
        distance_meters=round(distance_meters, 2) if distance_meters is not None else None,
        within_time_window=within_time_window,
        within_proximity=within_proximity,
        tier=tier,
        threshold_applied=auto_merge_threshold,
    )


def build_response(
    *,
    new_lat: float | None,
    new_lon: float | None,
    matches: list[DuplicateMatch],
    model_version: str,
) -> DuplicateCheckResponse:
    settings = get_settings()
    gps_available = new_lat is not None and new_lon is not None

    if not matches:
        return DuplicateCheckResponse(
            is_duplicate=False,
            parent_complaint_id=None,
            similarity_score=0.0,
            requires_manual_review=False,
            match_tier=MatchTier.NO_CANDIDATES,
            threshold_used=(
                settings.duplicate_auto_merge_similarity_threshold if gps_available
                else settings.duplicate_no_gps_similarity_threshold
            ),
            gps_available=gps_available,
            model_version=model_version,
            top_matches=[],
            raw_output={"candidate_count": 0, "skipped_candidates": []},
        )

    # Rank: AUTO_MERGE beats MANUAL_REVIEW beats NOT_DUPLICATE, ties broken by similarity.
    tier_rank = {MatchTier.AUTO_MERGE: 2, MatchTier.MANUAL_REVIEW: 1, MatchTier.NOT_DUPLICATE: 0}
    ranked = sorted(matches, key=lambda m: (tier_rank[m.tier], m.similarity_score), reverse=True)
    best = ranked[0]

    return DuplicateCheckResponse(
        is_duplicate=best.tier == MatchTier.AUTO_MERGE,
        parent_complaint_id=best.complaint_id if best.tier == MatchTier.AUTO_MERGE else None,
        similarity_score=best.similarity_score,
        requires_manual_review=best.tier == MatchTier.MANUAL_REVIEW,
        match_tier=best.tier,
        threshold_used=best.threshold_applied,
        gps_available=gps_available,
        model_version=model_version,
        top_matches=ranked[:5],
        raw_output={
            "candidate_count": len(matches),
            "best_match": best.model_dump(mode="json"),
            "all_matches": [m.model_dump(mode="json") for m in ranked],
        },
    )
