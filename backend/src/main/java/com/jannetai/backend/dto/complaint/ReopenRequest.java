package com.jannetai.backend.dto.complaint;

import jakarta.validation.constraints.Size;

/** Gap-backlog Patch 41: optional "why is it not resolved" for a citizen reopen/dispute. */
public record ReopenRequest(@Size(max = 200) String reason) {
}
