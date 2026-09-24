-- V16__create_otp_verifications.sql
-- OTP records backing the Authentication Module (SRS 15.2): mobile/email
-- verification at registration, MFA at login for Admin/Super Admin, and
-- password reset. Added in Phase 4 — no OTP storage existed in V1-V15
-- because Phase 3 was skeleton-only (no auth logic yet).
--
-- DESIGN NOTE: otp_code is stored hashed (bcrypt), never in plaintext,
-- consistent with Security Section 27.3 ("sensitive data at rest ... hashed
-- using industry-standard algorithms"). A raw 6-digit OTP is low-entropy,
-- but hashing still avoids a plaintext-secret column and costs nothing.
--
-- DESIGN NOTE: user_id is nullable because the registration-verification
-- OTP is issued for a user row that already exists by that point (POST
-- /auth/register creates the user first per SRS Section 18 table, status
-- ACTIVE-but-mobile_verified_at NULL), so in practice it is always
-- populated; NULL is permitted only defensively (never populated by any
-- code path in this phase) and to allow FK-safe cleanup if a registration
-- is later abandoned/deleted by a future admin tool.

CREATE TABLE otp_verifications (
    otp_id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NULL,
    mobile_number       VARCHAR(15)     NOT NULL,
    purpose             VARCHAR(30)     NOT NULL,
    otp_code_hash       VARCHAR(255)    NOT NULL,
    attempt_count       INT             NOT NULL DEFAULT 0,
    max_attempts        INT             NOT NULL DEFAULT 5,
    expires_at          TIMESTAMP       NOT NULL,
    consumed_at         TIMESTAMP       NULL,
    request_ip          VARCHAR(45)     NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_otp_verifications_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_otp_verifications_purpose CHECK (
        purpose IN ('REGISTRATION', 'LOGIN_MFA', 'PASSWORD_RESET')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OTP codes for registration verification, login MFA, and password reset (SRS 15.2, Phase 4)';

CREATE INDEX idx_otp_verifications_mobile_purpose
    ON otp_verifications (mobile_number, purpose);
CREATE INDEX idx_otp_verifications_user_id ON otp_verifications (user_id);
CREATE INDEX idx_otp_verifications_expires_at ON otp_verifications (expires_at);
