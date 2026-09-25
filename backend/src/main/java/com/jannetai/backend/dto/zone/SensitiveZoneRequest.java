package com.jannetai.backend.dto.zone;

import com.jannetai.backend.entity.enums.SensitiveZoneType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Audit GAP-033: Admin records a school / hospital / high-traffic road as a circle. */
public record SensitiveZoneRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull SensitiveZoneType zoneType,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @Min(10) @Max(5000) Integer radiusMeters
) {
}
