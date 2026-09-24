"""
Gap-backlog Patch 27 (Sep 2026 audit): duplicate-detection evaluation
harness, exercising app/services/duplicate_service.py's real dHash +
GPS/time-window logic against the six scenarios the patch names:

    same image, same location, different day
    same image, different angle
    nearby complaint (same issue, different citizen)
    different complaint (should NOT match)

Honest scope: no real dataset of actual duplicate citizen photos exists
in this repo, so every test case here is a SYNTHETICALLY constructed
near-duplicate pair (a base image + a simulated recompression/crop/
brightness perturbation) or a genuinely different synthetic image - not
real photos of the same pothole from two different citizens. This
exercises the ALGORITHM (dHash + tiering logic) for real, with real
measured similarity scores - it does not, and cannot, tell you how well
this performs against real-world citizen photo variation (different
phone cameras, real lighting, real angles). That needs a real labeled
dataset, which is the actual next step this script's results point to.

Run: python scripts/evaluate_duplicate_detection.py
"""
from __future__ import annotations

import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))  # so `python scripts/evaluate_duplicate_detection.py` works without PYTHONPATH

from datetime import datetime, timedelta, timezone

import numpy as np
from PIL import Image, ImageEnhance

from app.config import get_settings
from app.schemas.duplicate_check import DuplicateCandidate
from app.services.duplicate_service import compute_perceptual_hash, score_candidate


def _make_base_image(seed: int) -> Image.Image:
    rng = np.random.default_rng(seed)
    arr = rng.integers(0, 255, (480, 640, 3), dtype=np.uint8)
    arr[150:280, 200:400] = 15  # a dark "pothole-shaped" patch
    return Image.fromarray(arr)


def _to_bytes(img: Image.Image, quality: int = 90) -> bytes:
    buf = io.BytesIO()
    img.convert("RGB").save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def _recompress(img: Image.Image) -> bytes:
    return _to_bytes(img, quality=40)  # simulates a different phone's JPEG compression


def _crop_and_resize(img: Image.Image) -> bytes:
    w, h = img.size
    cropped = img.crop((int(w * 0.05), int(h * 0.05), w, h))  # simulates a different framing/angle
    return _to_bytes(cropped.resize((w, h)))


def _brighten(img: Image.Image) -> bytes:
    return _to_bytes(ImageEnhance.Brightness(img).enhance(1.4))  # simulates different lighting


def run() -> None:
    settings = get_settings()
    now = datetime.now(timezone.utc)
    base = _make_base_image(seed=1)
    unrelated = _make_base_image(seed=99)

    base_hash = compute_perceptual_hash(_to_bytes(base))

    scenarios = [
        ("Same image, same location, different day (2 days old)", _to_bytes(base), 12.5000, 77.5000, now - timedelta(days=2), True),
        ("Same issue, different angle/crop", _crop_and_resize(base), 12.5000, 77.5000, now - timedelta(hours=6), True),
        ("Same issue, recompressed (different phone)", _recompress(base), 12.5001, 77.5001, now - timedelta(hours=1), True),
        ("Same issue, different lighting", _brighten(base), 12.5000, 77.5000, now - timedelta(hours=3), True),
        ("Nearby complaint, same issue (60m away - outside 50m proximity)", _to_bytes(base), 12.5006, 77.5006, now - timedelta(hours=2), False),  # expected NOT auto-merge: outside proximity radius
        ("Genuinely different complaint (different location, different image)", _to_bytes(unrelated), 12.6000, 77.6000, now - timedelta(hours=1), False),
        ("Same image but 45 days old (outside time window)", _to_bytes(base), 12.5000, 77.5000, now - timedelta(days=45), False),
    ]

    print(f"{'Scenario':<65} {'Similarity':>10} {'Tier':>15} {'Expected dup?':>14} {'Result':>8}")
    print("-" * 118)

    true_dup = false_dup = missed_dup = correct = 0
    for label, photo_bytes, lat, lon, created_at, expect_duplicate_ish in scenarios:
        candidate_hash = compute_perceptual_hash(photo_bytes)
        candidate = DuplicateCandidate(
            image_base64="unused-hash-computed-directly",
            complaint_id=1,
            reference_number="JN-2026-000001",
            latitude=lat,
            longitude=lon,
            created_at=created_at,
        )
        match = score_candidate(
            new_hash=base_hash,
            new_lat=12.5000,
            new_lon=77.5000,
            new_now=now,
            candidate=candidate,
            candidate_hash=candidate_hash,
            settings=settings,
        )
        predicted_dup_ish = match.tier.value in ("AUTO_MERGE", "MANUAL_REVIEW")
        result = "match" if predicted_dup_ish == expect_duplicate_ish else "MISMATCH"
        if result == "match":
            correct += 1
        elif predicted_dup_ish and not expect_duplicate_ish:
            false_dup += 1
        elif not predicted_dup_ish and expect_duplicate_ish:
            missed_dup += 1

        print(f"{label:<65} {match.similarity_score:>10.1f} {match.tier.value:>15} {str(expect_duplicate_ish):>14} {result:>8}")

    print("-" * 118)
    print(f"Correct: {correct}/{len(scenarios)}  False duplicates: {false_dup}  Missed duplicates: {missed_dup}")
    print(f"\nThresholds used: auto_merge={settings.duplicate_auto_merge_similarity_threshold}, "
          f"manual_review={settings.duplicate_manual_review_similarity_threshold}, "
          f"proximity={settings.duplicate_proximity_meters}m, time_window={settings.duplicate_time_window_days}d")


if __name__ == "__main__":
    run()
