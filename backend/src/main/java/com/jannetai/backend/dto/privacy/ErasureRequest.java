package com.jannetai.backend.dto.privacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Audit GAP-041: self-service erasure is confirmed with the account password. */
public record ErasureRequest(@NotBlank String password) {
    /** SUPER_ADMIN erasure on behalf of a citizen (request received outside the app). */
    public record OnBehalf(@NotBlank @Size(max = 500) String reference) {
    }
}
