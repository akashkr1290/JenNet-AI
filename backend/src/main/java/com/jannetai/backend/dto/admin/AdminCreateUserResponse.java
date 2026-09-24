package com.jannetai.backend.dto.admin;

import com.jannetai.backend.dto.auth.UserProfileResponse;

/**
 * POST /api/v1/admin/users response. {@code temporaryPassword} is
 * returned exactly once, in this response only - it is never stored in
 * plaintext (only its BCrypt hash is persisted, same as every other
 * password in this system) and is never retrievable again. See
 * AdminUserService's Javadoc for why this design was chosen over emailing/
 * SMS-ing it (this design predates a real delivery channel; Phase 15's
 * EmailGatewayClient/SmsGatewayClient exist now but this Admin-provisioning
 * flow was deliberately left as-is rather than retrofitted - see
 * AdminUserService's Javadoc).
 */
public record AdminCreateUserResponse(
        UserProfileResponse user,
        String temporaryPassword
) {
}
