package com.jannetai.backend.dto.complaint;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppealRequest(
        @NotBlank @Size(min = 10, max = 1000) String reason
) {
}
