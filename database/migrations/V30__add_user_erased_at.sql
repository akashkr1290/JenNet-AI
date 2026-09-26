-- V30__add_user_erased_at.sql
-- Audit GAP-041 (SRS 24 Compliance: "citizen data access/erasure request
-- handling"; SRS 27.2 PII protection).
--
-- An erased account keeps its row (complaints, status history and audit
-- logs reference it and are public-interest civic records), but its personal
-- data is replaced (name, mobile, e-mail, password) and it can never log in
-- or be reactivated. erased_at records when that happened; NULL = not erased.
ALTER TABLE users ADD COLUMN erased_at TIMESTAMP NULL;
