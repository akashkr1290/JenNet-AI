# Duplicate Detection Evaluation

Gap-backlog Patch 27 (Sep 2026 audit) deliverable. Results below were
produced by actually running `scripts/evaluate_duplicate_detection.py`
in this sandbox against the real `app/services/duplicate_service.py`
algorithm (dHash perceptual hashing + GPS/time-window tiering) - not
estimated.

## Honest scope

No real dataset of actual duplicate citizen photos exists in this repo.
Every scenario below is a **synthetically constructed** near-duplicate
pair (a base synthetic image + a simulated recompression/crop/brightness
perturbation) or a genuinely different synthetic image - this validates
the *algorithm and tiering logic* for real, with real measured
similarity scores, but does not measure real-world performance against
actual variation between two different citizens' phone cameras, real
lighting, or real framing differences. **Real next step**: run this same
harness against an actual small set of real duplicate/non-duplicate
complaint photo pairs once any exist in the system.

## Results

| Scenario | Similarity | Tier | Expected | Result |
|---|---:|---|---|---|
| Same image, same location, 2 days old | 100.0 | AUTO_MERGE | duplicate | ✅ correct |
| Same issue, different angle/crop | 73.4 | MANUAL_REVIEW | duplicate | ✅ correct |
| Same issue, recompressed (different phone) | 93.8 | AUTO_MERGE | duplicate | ✅ correct |
| Same issue, different lighting | 87.5 | AUTO_MERGE | duplicate | ✅ correct |
| Nearby complaint, 60m away (outside 50m proximity) | 100.0 | NOT_DUPLICATE | not duplicate | ✅ correct |
| Genuinely different complaint | 57.8 | NOT_DUPLICATE | not duplicate | ✅ correct |
| Same image, 45 days old (outside 30-day window) | 100.0 | NOT_DUPLICATE | not duplicate | ✅ correct |

**7/7 correct, 0 false duplicates, 0 missed duplicates** on this
synthetic suite.

Thresholds exercised (current `app/config.py` defaults):
`auto_merge_similarity=80.0`, `manual_review_similarity=60.0`,
`proximity=50.0m`, `time_window=30 days`.

## What this confirms

- The dHash similarity metric behaves sensibly under simulated
  recompression (93.8), cropping (73.4), and lighting changes (87.5) -
  all meaningfully below the 100.0 "identical bytes" score but still
  correctly bucketed as AUTO_MERGE or MANUAL_REVIEW.
- The proximity boundary (50m) and time-window boundary (30 days) both
  correctly override a perfect 100.0 similarity score into
  NOT_DUPLICATE - confirming the AND logic in `score_candidate` (SRS
  15.6 Business Rules: similarity AND proximity AND time window) isn't
  short-circuited by similarity alone.
- A completely unrelated image still scored 57.8 similarity against the
  base image (dHash's collision floor for two random 640×480 noise
  images), safely under even the manual-review threshold of 60.0 - but
  worth noting this ceiling isn't 0: dHash is not immune to coincidental
  similarity on real photos with similar overall composition (e.g. two
  different grey road photos taken in similar light).

## Not yet tuned

The current thresholds (80/60/50m/30d) are `app/config.py` defaults,
not thresholds tuned against real measured false-positive/false-negative
rates - this evaluation validates the *mechanism* works as designed, it
does not constitute the threshold-tuning-against-real-data the original
patch also asked for, which requires real production complaint data this
sandbox doesn't have.
