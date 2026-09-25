package com.jannetai.backend.dto.admin;

import jakarta.validation.constraints.NotNull;

/** Audit GAP-031: Admin accepts an out-of-jurisdiction complaint into an active ward. */
public record JurisdictionAcceptRequest(@NotNull Long wardId) {
}
