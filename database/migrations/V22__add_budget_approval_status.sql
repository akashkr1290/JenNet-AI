-- V22__add_budget_approval_status.sql
-- Gap-backlog Patch 15 (Sep 2026 strict recheck): the patch asks for
-- budgetApprovalStatus and approvedAt plus a Reject action. Previously
-- approval was recorded only as "approved_by IS NOT NULL", with no time and
-- no way to record a rejection. Separate ALTER statements (not one
-- comma-joined ALTER) so the same file runs on MySQL and on H2's MySQL mode
-- used by the backend test profile.
ALTER TABLE budget ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE budget ADD COLUMN approved_at TIMESTAMP NULL;
ALTER TABLE budget ADD CONSTRAINT chk_budget_approval_status
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));
-- Existing approvals keep their meaning; their approval time was never
-- recorded, so approved_at stays NULL for them rather than being invented.
UPDATE budget SET approval_status = 'APPROVED' WHERE approved_by IS NOT NULL;
