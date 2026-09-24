"""
Request/response schemas for POST /api/v1/ai/duplicate-check
(SRS 20.3, 15.6 Duplicate Detection Module, 21.5).

DECISION - candidates travel in the request body (ADDITION beyond the
literal SRS 20.3 contract, which only lists
`{complaint_id, image_embedding, latitude, longitude}`): SRS 21.5 Input is
"image embedding vector, GPS coordinates, timestamp" for the *new*
submission only, with no explicit carrier for the "existing open
complaints in the same ward" (15.6 Inputs) this service is supposed to
compare against. ARCHITECTURE.md Section 2.3 (locked Phase 1) is explicit
that this service is "stateless with respect to business data ... does not
itself own the complaint record, MySQL" - so it cannot look candidates up
itself the way a stateful service could. The caller (Spring Boot, which
does own the complaint record) must supply the candidate set. This mirrors
exactly how Phase 7/8 resolved the image_url/image_base64 gap for
/classify: a documented, backend-facing addition to the literal contract,
not a deviation from the architecture. Recorded in
PROJECT_INTEGRATION.md Section 6.

`image_embedding` (the SRS's literal field name) is realized here as
perceptual-hash-comparable image bytes (`image_url`/`image_base64`, same
pattern as ClassifyRequest), not a learned embedding vector - see
app/services/duplicate_service.py's module docstring for why: no trained
embedding/YOLO model ships with this repo (ARCHITECTURE.md Section 5,
unchanged since Phase 7), so this service computes a perceptual hash
(difference hash) instead. SRS 15.6 Features literally lists "image
similarity comparison (perceptual hashing / embedding similarity)" as
either being acceptable - perceptual hashing is the one that needs no
trained weights and is honestly implementable in this environment today.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field, model_validator


class MatchTier(str, Enum):
    """SRS 21.5 Confidence Score bands."""

    AUTO_MERGE = "AUTO_MERGE"
    MANUAL_REVIEW = "MANUAL_REVIEW"
    NOT_DUPLICATE = "NOT_DUPLICATE"
    NO_CANDIDATES = "NO_CANDIDATES"


class _ImageSource(BaseModel):
    """Shared exactly-one-of-image_url/image_base64 validation (mirrors ClassifyRequest)."""

    image_url: str | None = Field(default=None, description="Fetchable reference to a stored complaint image.")
    image_base64: str | None = Field(default=None, description="Base64-encoded image bytes, no data URI prefix required.")

    @model_validator(mode="after")
    def _exactly_one_image_source(self) -> "_ImageSource":
        if bool(self.image_url) == bool(self.image_base64):
            raise ValueError("Provide exactly one of image_url or image_base64.")
        return self


class DuplicateCandidate(_ImageSource):
    """
    One existing open complaint the new submission is compared against.
    Supplied by the backend (see module docstring) - this service never
    looks candidates up itself.
    """

    complaint_id: int
    reference_number: str | None = Field(
        default=None, description="Human-readable tracking code, surfaced in raw_output for audit readability only."
    )
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    created_at: datetime = Field(..., description="Candidate complaint's created_at, for the 30-day time-window filter.")


class DuplicateCheckRequest(_ImageSource):
    # SRS 20.3 literal contract fields.
    complaint_id: int
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)

    # ADDITION beyond the literal contract - see module docstring.
    candidates: list[DuplicateCandidate] = Field(
        default_factory=list,
        description=(
            "Existing open complaints (SRS 15.6 Inputs: 'in the same ward') "
            "for the new submission to be compared against. An empty list "
            "is valid - it means no candidates exist yet (e.g. the first "
            "complaint in a ward) and the response is NO_CANDIDATES/not a "
            "duplicate, not an error."
        ),
    )


class DuplicateMatch(BaseModel):
    """One scored comparison against a single candidate - always returned for every candidate, for audit (SRS 15.6/19.6)."""

    complaint_id: int
    reference_number: str | None = None
    similarity_score: float = Field(..., ge=0, le=100)
    distance_meters: float | None = Field(
        default=None, description="Haversine distance in meters, or null if either side is missing GPS."
    )
    within_time_window: bool
    within_proximity: bool | None = Field(
        default=None, description="Null when distance_meters is null (GPS unavailable on one side)."
    )
    tier: MatchTier
    threshold_applied: float = Field(
        ..., description="The auto-merge similarity threshold used for this specific comparison (90 if either side lacked GPS, else 80)."
    )


class DuplicateCheckResponse(BaseModel):
    # --- SRS 15.6/21.5/20.3 literal output fields ---
    is_duplicate: bool = Field(
        ..., description="True only for the best match's tier == AUTO_MERGE (SRS 15.6: '>=80% AND within 50m/30d')."
    )
    parent_complaint_id: int | None = Field(
        default=None, description="The matched complaint to merge into. Set for AUTO_MERGE; null otherwise."
    )
    similarity_score: float = Field(
        ..., ge=0, le=100, description="The best candidate match's similarity score; 0 if there were no candidates."
    )

    # --- Additions beyond the literal SRS output list, needed for the
    # caller to implement SRS 15.6's routing rule the same way /classify's
    # requires_manual_review addition does (PROJECT_INTEGRATION.md Section 6).
    requires_manual_review: bool = Field(
        ...,
        description=(
            "True when the best match's tier is MANUAL_REVIEW (60-80% "
            "similarity within the window - SRS 15.6 Exceptions: 'routed "
            "to the Verification Team for manual confirmation rather than "
            "auto-merged or auto-rejected')."
        ),
    )
    match_tier: MatchTier = Field(..., description="The best match's tier, or NO_CANDIDATES if candidates was empty.")
    threshold_used: float = Field(
        ..., description="Which auto-merge similarity threshold applied - 90 if either side lacked GPS, else 80."
    )
    gps_available: bool = Field(..., description="False if the new submission's own lat/lon was not supplied.")
    model_version: str = Field(..., description="SRS 19.6 predictions.model_version value for this pipeline.")
    top_matches: list[DuplicateMatch] = Field(
        default_factory=list,
        description="Every candidate's individual score, best-first (SRS 21.1-style fallback surfacing, capped to top 5).",
    )
    raw_output: dict[str, Any] = Field(
        ..., description="Full comparison detail for audit/retraining - maps onto predictions.raw_model_output (SRS 19.6)."
    )
