package com.jannetai.backend.dto.notification;

import com.jannetai.backend.entity.enums.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeviceTokenRequest(
        @NotBlank String deviceToken,
        @NotNull DevicePlatform platform
) {
}
