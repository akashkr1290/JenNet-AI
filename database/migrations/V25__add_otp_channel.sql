-- V25__add_otp_channel.sql
-- Audit GAP-004 (SRS 15.1/15.2 "OTP-based mobile/email verification"): an OTP
-- can now be delivered by e-mail as well as by SMS. The row stays keyed by the
-- account's mobile number (the verify endpoints are unchanged); channel records
-- where the code was sent so a successful registration verification marks the
-- right identifier (mobile_verified_at or email_verified_at) as verified.
-- Existing rows are SMS, which is exactly what they were.

ALTER TABLE otp_verifications
    ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'SMS' AFTER purpose;

ALTER TABLE otp_verifications
    ADD CONSTRAINT chk_otp_verifications_channel CHECK (channel IN ('SMS', 'EMAIL'));
