package com.jannetai.backend.dto.ward;

import jakarta.validation.constraints.NotNull;

/** Audit GAP-020: activate/deactivate a ward or department (soft - rows are never deleted). */
public record ActiveFlagRequest(@NotNull Boolean active) {
}
