package com.jannetai.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/register (SRS 18 table; Screen 15.1 fields). */
public record RegisterRequest(

        @NotBlank @Size(max = 100)
        String fullName,

        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a valid 10-digit mobile number")
        String mobileNumber,

        @Email @Size(max = 150)
        String email,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$",
                message = "must be at least 8 characters and include upper case, lower case, a digit, and a special character"
        )
        String password,

        Long wardId
) {
}
