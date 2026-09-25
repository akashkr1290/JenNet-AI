-- V24__add_notification_delivery_skipped.sql
-- Audit GAP-022: a notification whose channel is not configured (SMS/email/
-- push disabled) or whose recipient has no address/device was stored as
-- DELIVERED although nothing was transmitted. Such rows are now SKIPPED.
-- Additive only: existing rows and values stay valid.

ALTER TABLE notifications
    DROP CHECK chk_notifications_delivery_status;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_delivery_status CHECK (
        delivery_status IN ('PENDING', 'DELIVERED', 'FAILED', 'SKIPPED')
    );
