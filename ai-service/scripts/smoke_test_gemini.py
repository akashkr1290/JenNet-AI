"""
Gap-backlog Patch 4 (Sep 2026 audit): Gemini integration smoke test,
exercising the three cases the patch names:

    Case 1: YOLO -> Pothole, Gemini -> Pothole (agreement)
    Case 2: YOLO -> Pothole, Gemini -> different classification (disagreement)
    Case 3: YOLO -> low confidence (Gemini not meaningfully consulted)

Honest scope: this sandbox has no GEMINI_API_KEY and no outbound network
to Google's API, so Cases 1/2 (which need a REAL Gemini response to
actually agree or disagree with) cannot be genuinely exercised here -
running this script in this sandbox only proves the no-key FALLBACK path
works (real, verified below), not that Gemini improves accuracy. That
claim can only be tested with a real API key against real images, which
is exactly what Gap-backlog Patch 4's own "Important" note warns not to
claim without doing. Run this script with a real GEMINI_API_KEY set to
actually exercise Cases 1/2.
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings
from app.services import gemini_service


async def run() -> None:
    settings = get_settings()
    has_key = bool(settings.gemini_api_key)
    print(f"GEMINI_API_KEY configured: {has_key}")

    # Case 3 first: no top candidate at all (YOLO below detection
    # threshold) - Gemini should not be meaningfully consulted regardless
    # of whether a key is configured, since there's nothing to cross-
    # validate against.
    result = await gemini_service.cross_validate(
        image_bytes=b"not-a-real-image-bytes-placeholder",
        top_candidate_class=None,
        candidate_classes=[],
        citizen_description=None,
    )
    print(f"\nCase 3 (no top candidate): used={result.used}, fallback_reason={result.fallback_reason!r}")
    assert result.used is False, "Gemini must not be consulted with no top candidate"

    # Cases 1/2 both call cross_validate WITH a top candidate. Without a
    # real key, this genuinely exercises (and here, asserts) the
    # documented fallback path - WITH a real key, the same call would
    # exercise the real agree/disagree logic instead, and this script's
    # printed result would show which of Case 1 or Case 2 actually
    # happened for whatever image is passed in.
    result = await gemini_service.cross_validate(
        image_bytes=b"not-a-real-image-bytes-placeholder",
        top_candidate_class="pothole",
        candidate_classes=["pothole", "open_manhole"],
        citizen_description="There's a large pothole on the main road",
    )
    print(f"\nCase 1/2 (top candidate='pothole'): used={result.used}, "
          f"agrees_with_top_candidate={result.agrees_with_top_candidate}, "
          f"fallback_reason={result.fallback_reason!r}")

    if not has_key:
        assert result.used is False and result.fallback_reason == "GEMINI_API_KEY not configured", (
            "Expected the documented no-key fallback path"
        )
        print("\nNo API key configured - fallback path verified. "
              "Set GEMINI_API_KEY and re-run against a real image to exercise Cases 1/2 for real.")
    else:
        print(f"\nReal API key was used - agrees_with_top_candidate={result.agrees_with_top_candidate} "
              "is Case 1 (True) or Case 2 (False) for real, against whatever image bytes were passed above. "
              "Replace the placeholder image_bytes with a real photo before drawing any conclusions.")


if __name__ == "__main__":
    asyncio.run(run())
