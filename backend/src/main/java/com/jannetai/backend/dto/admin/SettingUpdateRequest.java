package com.jannetai.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * PATCH /api/v1/admin/settings/{key} body. Range/type validation is
 * key-specific (see {@link com.jannetai.backend.service.admin.PlatformSettingKey#validate}),
 * so only blankness is checked here at the bean-validation layer.
 */
public record SettingUpdateRequest(
        @NotBlank
        String value
) {
}
